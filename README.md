```markdown
# Invoice Data Extraction Function

## Purpose

This project is designed to extract invoice data from documents stored in an S3 bucket using AWS Textract. The extracted data is then sent to an SQS queue for further processing. The project is implemented as an AWS Lambda function.

## Deployment

### Prerequisites

- AWS account
- AWS CLI configured
- Gradle installed
- Java 11 or higher installed

### Steps to Deploy

1. **Build the Project:**

   Navigate to the project directory and run the following command to build the project:

   ```sh
   ./gradlew build
   ```

2. **Package the Lambda Function:**

   Use the AWS CLI to package the Lambda function:

   ```sh
   aws cloudformation package --template-file template.yaml --s3-bucket YOUR_S3_BUCKET --output-template-file packaged-template.yaml
   ```

3. **Deploy the Lambda Function:**

   Deploy the packaged Lambda function using AWS CloudFormation:

   ```sh
   aws cloudformation deploy --template-file packaged-template.yaml --stack-name InvoiceDataExtractionStack --capabilities CAPABILITY_IAM
   ```

### Environment Variables

The `.env` file contains the following environment variables:

- `AWS_SQS_QUEUE_URL`: The URL of the SQS queue where the extracted invoice data will be sent.

### Testing Locally

To test the function locally, you can use the AWS SAM CLI. Follow these steps:

1. **Install AWS SAM CLI:**

   Follow the instructions [here](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/install-sam-cli.html) to install the AWS SAM CLI.

2. **Start the Local API:**

   Navigate to the project directory and run the following command to start the local API:

   ```sh
   sam local start-api
   ```

3. **Invoke the Function:**

   You can invoke the function locally using the following command:

   ```sh
   sam local invoke "InvoiceHandler" -e events/s3-event.json
   ```

   Make sure to replace `events/s3-event.json` with the path to your test event file.

## Conclusion

This project provides a robust solution for extracting and processing invoice data using AWS services. Follow the steps above to deploy and test the function in your environment.
```