package com.epam.demo.invoice;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.*;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;

public class InvoiceHandler implements RequestHandler<S3Event, String> {
    private final TextractClient textractClient = TextractClient.builder().build();
    private final SqsClient sqsClient = SqsClient.builder().build();

    @Override
    public String handleRequest(S3Event s3Event, Context context) {
        try {
            // Get S3 bucket and key from the event
            S3EventNotificationRecord record = s3Event.getRecords().getFirst();
            String bucket = record.getS3().getBucket().getName();
            String key = record.getS3().getObject().getKey();

            context.getLogger().log("Processing file: " + key + " from bucket: " + bucket);

            // Create document request
            S3Object s3Object = S3Object.builder()
                    .bucket(bucket)
                    .name(key)
                    .build();

            DocumentLocation documentLocation = DocumentLocation.builder()
                    .s3Object(s3Object)
                    .build();

            // Start the analysis job
            StartDocumentAnalysisRequest analysisRequest = StartDocumentAnalysisRequest.builder()
                    .documentLocation(documentLocation)
                    .featureTypes(FeatureType.TABLES)
                    .build();

            StartDocumentAnalysisResponse analysisResponse = textractClient.startDocumentAnalysis(analysisRequest);
            String jobId = analysisResponse.jobId();
            context.getLogger().log("Started analysis job with ID: " + jobId);

            // Wait for the job to complete
            GetDocumentAnalysisResponse result;
            do {
                result = textractClient.getDocumentAnalysis(
                        GetDocumentAnalysisRequest.builder()
                                .jobId(jobId)
                                .build()
                );
                Thread.sleep(1000);
            } while (result.jobStatus() == JobStatus.IN_PROGRESS);

            if (result.jobStatus() == JobStatus.SUCCEEDED) {
                // Process results
                List<Block> blocks = new ArrayList<>(result.blocks());

                // Get all pages if they exist
                String nextToken = result.nextToken();
                while (nextToken != null) {
                    GetDocumentAnalysisResponse nextResult = textractClient.getDocumentAnalysis(
                            GetDocumentAnalysisRequest.builder()
                                    .jobId(jobId)
                                    .nextToken(nextToken)
                                    .build()
                    );
                    blocks.addAll(nextResult.blocks());
                    nextToken = nextResult.nextToken();
                }

                Optional<Block> detailsTable = getItemsDetailTable(blocks);

                // Process the details table
                if (detailsTable.isPresent()) {
                    context.getLogger().log("Processing details table");

                    try {
                        List<InvoiceItem> items = processTable(detailsTable.get(), blocks, context);
                        ObjectMapper mapper = new ObjectMapper();

                        String itemsJson = mapper.writeValueAsString(items);
                        sqsClient.sendMessage(
                                SendMessageRequest.builder()
                                        .queueUrl(System.getenv("AWS_SQS_QUEUE_URL"))
                                        .messageBody(itemsJson)
                                        .build()
                        );
                        return itemsJson;
                    } catch (JsonProcessingException e) {
                        context.getLogger().log("Error converting items to JSON: " + e.getMessage());
                    }

                } else {
                    context.getLogger().log("No details table found");
                }

                return "{}";
            } else {
                return "Analysis job failed with status: " + result.jobStatus();
            }

        } catch (Exception e) {
            context.getLogger().log("Error processing document: " + e.getMessage());
            return "Error processing document: " + e.getMessage();
        }
    }

    private Optional<Block> getItemsDetailTable(List<Block> blocks) {
        return blocks.stream()
                .filter(block -> block.blockType() == BlockType.TABLE)
                .filter(table -> table.entityTypes().contains(EntityType.STRUCTURED_TABLE))
                .findFirst();
    }

    private List<InvoiceItem> processTable(Block table, List<Block> blocks, Context context) {
        context.getLogger().log("Processing table: " + table.id());

        List<Block> tableCells = table.relationships().stream()
                .filter(relationship -> relationship.type() == RelationshipType.CHILD)
                .flatMap(relationship -> relationship.ids().stream())
                .map(id -> findBlockById(blocks, id))
                .filter(block -> block.blockType() == BlockType.CELL)
                .toList();

        List<InvoiceItem> invoiceItems = new ArrayList<>();

        int skipRowIndex = -1;

        String invoiceCode = null;
        String invoiceDescription = null;
        int invoiceQuantity = 0;
        double invoiceUnitPrice = 0;
        double invoiceTotal = 0;

        for (Block cell : tableCells) {
            if (cell.rowIndex() != null && cell.rowIndex() != 1 && cell.rowIndex() != skipRowIndex && cell.columnIndex()
                    != null) {
                String cellContent = getCellContent(cell, blocks);

                if (cell.columnIndex() == 1) {
                    if (cellContent.isEmpty()) {
                        skipRowIndex = cell.rowIndex();
                        continue;
                    }

                    invoiceCode = cellContent;
                } else if (cell.columnIndex() == 2) {
                    invoiceDescription = cellContent;
                } else if (cell.columnIndex() == 4 && !cellContent.isEmpty()) {
                    invoiceQuantity = Integer.parseInt(cellContent);
                } else if (cell.columnIndex() == 6 && !cellContent.isEmpty()) {
                    invoiceUnitPrice  = Double.parseDouble(cellContent.replaceAll(",",""));
                } else if (cell.columnIndex() == 7 && !cellContent.isEmpty()) {
                    invoiceTotal = Double.parseDouble(cellContent.replaceAll(",",""));
                    invoiceItems.add(new InvoiceItem(invoiceCode, invoiceDescription, invoiceQuantity, invoiceUnitPrice,
                            invoiceTotal));
                    skipRowIndex = cell.rowIndex();
                }
            }
        }

        context.getLogger().log(String.format("Found %d Invoice items: ", invoiceItems.size()));
        return invoiceItems;
    }

    private String getCellContent(Block cell, List<Block> blocks) {
        if (cell.relationships() == null) {
            return "";
        }

        StringBuilder content = new StringBuilder();
        cell.relationships().stream()
                .filter(relationship -> relationship.type() == RelationshipType.CHILD)
                .flatMap(relationship -> relationship.ids().stream())
                .map(id -> findBlockById(blocks, id))
                .filter(block -> block != null && block.blockType() == BlockType.WORD)
                .forEach(word -> content.append(word.text()).append(" "));

        return content.toString().trim();
    }

    private Block findBlockById(List<Block> blocks, String id) {
        return blocks.stream()
                .filter(block -> block.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    private record InvoiceItem(String code, String description, double quantity, double unitPrice, double total) {
    }
}