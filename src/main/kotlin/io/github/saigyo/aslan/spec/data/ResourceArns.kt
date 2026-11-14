package io.github.saigyo.aslan.spec.data

import io.github.saigyo.aslan.spec.NamedDoc

/** An integration pattern: the suffix it adds to the ARN and how it is described. */
private enum class Pattern(val suffix: String, val describe: (String) -> String) {
    REQUEST_RESPONSE("", { "Calls $it and continues as soon as the service answers." }),
    SYNC(".sync", { "Calls $it and waits until the work it starts is complete." }),
    SYNC_JSON(".sync:2", { "Calls $it and waits until the child execution has finished; its output is returned as JSON, not as a string." }),
    CALLBACK(".waitForTaskToken", { "Calls $it with a task token and pauses until the token comes back through SendTaskSuccess or SendTaskFailure." }),
}

private val RR = Pattern.REQUEST_RESPONSE
private val SYNC = Pattern.SYNC
private val CALLBACK = Pattern.CALLBACK

/** One integration API: one entry per supported pattern, with ARNs of the form `arn:aws:states:::service:action`. */
private fun api(page: String, service: String, action: String, what: String, vararg patterns: Pattern): List<NamedDoc> =
    patterns.map { NamedDoc("arn:aws:states:::$service:$action${it.suffix}", it.describe(what), Doc.page(page)) }

/** The optimised service integrations and the HTTP task: what a Task state can name in `Resource` besides its own resources. */
internal val taskResourceArns: List<NamedDoc> = listOf(
    api("connect-api-gateway", "apigateway", "invoke", "an Amazon API Gateway endpoint", RR, CALLBACK),

    api("connect-athena", "athena", "startQueryExecution", "Amazon Athena StartQueryExecution", RR, SYNC),
    api("connect-athena", "athena", "stopQueryExecution", "Amazon Athena StopQueryExecution", RR),
    api("connect-athena", "athena", "getQueryExecution", "Amazon Athena GetQueryExecution", RR),
    api("connect-athena", "athena", "getQueryResults", "Amazon Athena GetQueryResults", RR),

    api("connect-batch", "batch", "submitJob", "AWS Batch SubmitJob", RR, SYNC),

    api("connect-bedrock", "bedrock", "invokeModel", "Amazon Bedrock InvokeModel", RR),
    api("connect-bedrock", "bedrock", "createModelCustomizationJob", "Amazon Bedrock CreateModelCustomizationJob", RR, SYNC),

    api("connect-bedrockagentcore", "bedrockagentcore", "invokeHarness", "Amazon Bedrock AgentCore InvokeHarness", RR),

    api("connect-codebuild", "codebuild", "startBuild", "AWS CodeBuild StartBuild", RR, SYNC),
    api("connect-codebuild", "codebuild", "stopBuild", "AWS CodeBuild StopBuild", RR),
    api("connect-codebuild", "codebuild", "batchDeleteBuilds", "AWS CodeBuild BatchDeleteBuilds", RR),
    api("connect-codebuild", "codebuild", "batchGetReports", "AWS CodeBuild BatchGetReports", RR),
    api("connect-codebuild", "codebuild", "startBuildBatch", "AWS CodeBuild StartBuildBatch", RR, SYNC),
    api("connect-codebuild", "codebuild", "stopBuildBatch", "AWS CodeBuild StopBuildBatch", RR),
    api("connect-codebuild", "codebuild", "retryBuildBatch", "AWS CodeBuild RetryBuildBatch", RR, SYNC),
    api("connect-codebuild", "codebuild", "deleteBuildBatch", "AWS CodeBuild DeleteBuildBatch", RR),

    api("connect-databrew", "databrew", "startJobRun", "AWS Glue DataBrew StartJobRun", RR, SYNC),

    api("connect-ddb", "dynamodb", "getItem", "Amazon DynamoDB GetItem", RR),
    api("connect-ddb", "dynamodb", "putItem", "Amazon DynamoDB PutItem", RR),
    api("connect-ddb", "dynamodb", "deleteItem", "Amazon DynamoDB DeleteItem", RR),
    api("connect-ddb", "dynamodb", "updateItem", "Amazon DynamoDB UpdateItem", RR),

    api("connect-ecs", "ecs", "runTask", "Amazon ECS RunTask", RR, SYNC, CALLBACK),

    api("connect-eks", "eks", "createCluster", "Amazon EKS CreateCluster", RR, SYNC),
    api("connect-eks", "eks", "deleteCluster", "Amazon EKS DeleteCluster", RR, SYNC),
    api("connect-eks", "eks", "createFargateProfile", "Amazon EKS CreateFargateProfile", RR, SYNC),
    api("connect-eks", "eks", "deleteFargateProfile", "Amazon EKS DeleteFargateProfile", RR, SYNC),
    api("connect-eks", "eks", "createNodegroup", "Amazon EKS CreateNodegroup", RR, SYNC),
    api("connect-eks", "eks", "deleteNodegroup", "Amazon EKS DeleteNodegroup", RR, SYNC),
    api("connect-eks", "eks", "runJob", "a Kubernetes Job on an Amazon EKS cluster", RR, SYNC),
    api("connect-eks", "eks", "call", "the Kubernetes API of an Amazon EKS cluster", RR),

    api("connect-emr", "elasticmapreduce", "createCluster", "Amazon EMR createCluster", RR, SYNC),
    api("connect-emr", "elasticmapreduce", "setClusterTerminationProtection", "Amazon EMR setClusterTerminationProtection", RR),
    api("connect-emr", "elasticmapreduce", "terminateCluster", "Amazon EMR terminateCluster", RR, SYNC),
    api("connect-emr", "elasticmapreduce", "addStep", "Amazon EMR addStep", RR, SYNC),
    api("connect-emr", "elasticmapreduce", "cancelStep", "Amazon EMR cancelStep", RR),
    api("connect-emr", "elasticmapreduce", "modifyInstanceFleetByName", "Amazon EMR modifyInstanceFleetByName", RR),
    api("connect-emr", "elasticmapreduce", "modifyInstanceGroupByName", "Amazon EMR modifyInstanceGroupByName", RR),

    api("connect-emr-eks", "emr-containers", "createVirtualCluster", "Amazon EMR on EKS CreateVirtualCluster", RR),
    api("connect-emr-eks", "emr-containers", "deleteVirtualCluster", "Amazon EMR on EKS DeleteVirtualCluster", RR, SYNC),
    api("connect-emr-eks", "emr-containers", "startJobRun", "Amazon EMR on EKS StartJobRun", RR, SYNC),

    api("connect-emr-serverless", "emr-serverless", "createApplication", "Amazon EMR Serverless createApplication", RR, SYNC),
    api("connect-emr-serverless", "emr-serverless", "startApplication", "Amazon EMR Serverless startApplication", RR, SYNC),
    api("connect-emr-serverless", "emr-serverless", "stopApplication", "Amazon EMR Serverless stopApplication", RR, SYNC),
    api("connect-emr-serverless", "emr-serverless", "deleteApplication", "Amazon EMR Serverless deleteApplication", RR, SYNC),
    api("connect-emr-serverless", "emr-serverless", "startJobRun", "Amazon EMR Serverless startJobRun", RR, SYNC),
    api("connect-emr-serverless", "emr-serverless", "cancelJobRun", "Amazon EMR Serverless cancelJobRun", RR, SYNC),

    api("connect-eventbridge", "events", "putEvents", "Amazon EventBridge PutEvents", RR, CALLBACK),

    api("connect-glue", "glue", "startJobRun", "AWS Glue StartJobRun", RR, SYNC),

    api("connect-lambda", "lambda", "invoke", "an AWS Lambda function", RR, CALLBACK),

    api("connect-mediaconvert", "mediaconvert", "createJob", "AWS Elemental MediaConvert CreateJob", RR, SYNC),

    api("connect-sagemaker", "sagemaker", "createEndpoint", "Amazon SageMaker AI CreateEndpoint", RR),
    api("connect-sagemaker", "sagemaker", "createEndpointConfig", "Amazon SageMaker AI CreateEndpointConfig", RR),
    api("connect-sagemaker", "sagemaker", "createHyperParameterTuningJob", "Amazon SageMaker AI CreateHyperParameterTuningJob", RR, SYNC),
    api("connect-sagemaker", "sagemaker", "createLabelingJob", "Amazon SageMaker AI CreateLabelingJob", RR, SYNC),
    api("connect-sagemaker", "sagemaker", "createModel", "Amazon SageMaker AI CreateModel", RR),
    api("connect-sagemaker", "sagemaker", "createProcessingJob", "Amazon SageMaker AI CreateProcessingJob", RR, SYNC),
    api("connect-sagemaker", "sagemaker", "createTrainingJob", "Amazon SageMaker AI CreateTrainingJob", RR, SYNC),
    api("connect-sagemaker", "sagemaker", "createTransformJob", "Amazon SageMaker AI CreateTransformJob", RR, SYNC),
    api("connect-sagemaker", "sagemaker", "updateEndpoint", "Amazon SageMaker AI UpdateEndpoint", RR),

    api("connect-sns", "sns", "publish", "Amazon SNS Publish", RR, CALLBACK),

    api("connect-sqs", "sqs", "sendMessage", "Amazon SQS SendMessage", RR, CALLBACK),

    api("connect-stepfunctions", "states", "startExecution", "AWS Step Functions StartExecution",
        RR, SYNC, Pattern.SYNC_JSON, CALLBACK),

    api("call-https-apis", "http", "invoke", "a public HTTPS API", RR),
).flatten()

/** The Amazon S3 actions of ItemReader and ResultWriter. */
private val distributedMapResourceArns: List<NamedDoc> = listOf(
    NamedDoc("arn:aws:states:::s3:getObject", "Reads one Amazon S3 object, such as a CSV or JSON file, as the data set of a Distributed Map.",
        Doc.ITEM_READER),
    NamedDoc("arn:aws:states:::s3:listObjectsV2", "Lists the objects under an Amazon S3 bucket or prefix as the data set of a Distributed Map.",
        Doc.ITEM_READER),
    NamedDoc("arn:aws:states:::s3:putObject", "Stores the results of a Distributed Map in an Amazon S3 bucket.", Doc.RESULT_WRITER),
)

/** Every resource ARN the reference data describes. */
internal val optimisedResourceArns: List<NamedDoc> = taskResourceArns + distributedMapResourceArns
