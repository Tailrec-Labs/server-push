import com.google.inject.AbstractModule
import java.time.Clock

import services.{ApplicationTimer, AtomicCounter, Counter}
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.regions.Region
import com.amazonaws.services.dynamodbv2.document.DynamoDB
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient
import play.api.{Configuration, Environment, Logger}
import com.amazonaws.auth.AWSCredentials
import javax.inject.Provider
import javax.inject.Inject

import com.amazonaws.services.sns.AmazonSNSClient
import com.amazonaws.services.sns.AmazonSNS


/**
 * This class is a Guice module that tells Guice how to bind several
 * different types. This Guice module is created when the Play
 * application starts.

 * Play will automatically use any class called `Module` that is in
 * the root package. You can create modules in other locations by
 * adding `play.modules.enabled` settings to the `application.conf`
 * configuration file.
 */
class Module(env: Environment, config: Configuration) extends AbstractModule {
  
  lazy val awsCredentials = new BasicAWSCredentials(
        config.getString("aws.accessKeyId").get,
        config.getString("aws.secretAccessKey").get)
  lazy val awsRegion = Region.getRegion(Regions.valueOf(config.getString("aws.region").get))
  
  override def configure() = {
    bind(classOf[AWSCredentials]).toInstance{
      new BasicAWSCredentials(
        config.getString("aws.accessKeyId").get,
        config.getString("aws.secretAccessKey").get)
    }
    bind(classOf[Region]).toInstance{
      Region.getRegion(Regions.valueOf(config.getString("aws.region").get))
    }
    bind(classOf[AmazonSQS]).toProvider(classOf[AmazonSQSProvider]).asEagerSingleton()
    bind(classOf[AmazonSNS]).toProvider(classOf[AmazonSNSProvider]).asEagerSingleton()
    bind(classOf[DynamoDB]).toProvider(classOf[DynamoDBProvider]).asEagerSingleton()
    // Use the system clock as the default implementation of Clock
    bind(classOf[Clock]).toInstance(Clock.systemDefaultZone)
    // Ask Guice to create an instance of ApplicationTimer when the
    // application starts.
    bind(classOf[ApplicationTimer]).asEagerSingleton()
    // Set AtomicCounter as the implementation for Counter.
    bind(classOf[Counter]).to(classOf[AtomicCounter])
  }
  
}

@Deprecated
class AmazonSQSProvider @Inject()(credentials: AWSCredentials, region: Region) 
  extends Provider[AmazonSQS] {
  
  override def get() = {
    Logger.info("Initialing AmazonSQS Client...")
    val client = new AmazonSQSClient(credentials)
    client.setRegion(region)
    client
  }
}

class AmazonSNSProvider @Inject()(credentials: AWSCredentials, region: Region) 
  extends Provider[AmazonSNSClient] {
  
  override def get() = {
    Logger.info("Initialing AmazonSNS Client...")
    val client = new AmazonSNSClient(credentials)
    client.setRegion(region)
    client
  }
}

@Deprecated
class DynamoDBProvider @Inject()(credentials: AWSCredentials, region: Region) 
  extends Provider[DynamoDB] {
  
  override def get() = {
    Logger.info("Initialing DynamoDB Client...")
    val client = new AmazonDynamoDBClient(credentials)
    client.setRegion(region)
    new DynamoDB(client)
  }
}


