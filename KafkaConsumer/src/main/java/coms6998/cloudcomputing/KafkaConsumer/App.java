package coms6998.cloudcomputing.KafkaConsumer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.json.JSONException;
import org.json.JSONObject;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.auth.profile.ProfilesConfigFile;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.sns.AmazonSNSClient;
import com.amazonaws.services.sns.model.PublishRequest;
import com.amazonaws.services.sns.model.PublishResult;

/**
 * Hello world!
 *
 */
public class App {
	private static String baseURL = "http://access.alchemyapi.com/calls/text/TextGetTextSentiment";
	private static String key = "724912170947543d2de401b5b4b3707fb329d011";
	static AWSCredentials credentials;
	static ExecutorService executor;
	static String topicName = "tweetsentiments";
	static Properties props;

	public static void main(String[] args) {
		credentials = null;
		try {
			ProfilesConfigFile configFile = new ProfilesConfigFile(
					"aws_credentials.properties");
			credentials = new ProfileCredentialsProvider(configFile,
					"siddharth").getCredentials();
		} catch (Exception e) {
			throw new AmazonClientException(
					"Cannot load the credentials from the credential profiles file. "
							+ "Please make sure that your credentials file is at the correct "
							+ "location (~/.aws/credentials), and is in valid format.",
					e);
		}

		props = new Properties();

		props.put("bootstrap.servers", "localhost:9092");
		props.put("group.id", "test");
		props.put("enable.auto.commit", "true");
		props.put("auto.commit.interval.ms", "1000");
		props.put("session.timeout.ms", "30000");
		props.put("key.deserializer",
				"org.apache.kafka.common.serialization.StringDeserializer");
		props.put("value.deserializer",
				"org.apache.kafka.common.serialization.StringDeserializer");

		executor = Executors.newFixedThreadPool(5);

		for (int i = 0; i < 5; i++) {
			Runnable worker = new WorkerThread();
			executor.execute(worker);
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		// executor.shutdown();

		try {
			executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

	}

	static public class WorkerThread implements Runnable {

		KafkaConsumer<String, String> consumer;

		public WorkerThread() {
			System.out.println("Worker Created");
			consumer = new KafkaConsumer<String, String>(props);
			consumer.subscribe(Arrays.asList(topicName));
		}

		public void run() {
			// TODO Auto-generated method stub
			try {
				while (true) {
					processQueue();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		private void processQueue() throws IOException {
			ConsumerRecords<String, String> records = consumer.poll(100);
			for (ConsumerRecord<String, String> record : records) {
				System.out.println(record.value());
				JSONObject jsonObject;
				JSONObject response = null;
				try {
					jsonObject = new JSONObject(record.value());
				} catch (Exception e) {
					System.out.println("Catch");
					continue;
				}
				String text = jsonObject.getString("text");
				System.out.println(text);

				StringBuilder params = new StringBuilder();
				params.append("apikey=" + key);
				params.append("&text=" + URLEncoder.encode(text, "ASCII"));
				params.append("&outputMode=json");
				params.append("&language=english");
				params.append("&showSourceText=1");

				StringBuilder uri = new StringBuilder();
				uri.append(baseURL).append('?').append(params.toString());

				URL url = new URL(uri.toString());
				HttpURLConnection handle = (HttpURLConnection) url
						.openConnection();
				handle.setDoOutput(true);

				try {
					int status = handle.getResponseCode();
					System.out.println(status);
					switch (status) {
					case 200:
					case 201:
						BufferedReader br = new BufferedReader(
								new InputStreamReader(handle.getInputStream()));
						StringBuilder sb = new StringBuilder();
						String line;
						while ((line = br.readLine()) != null) {
							sb.append(line + "\n");
						}
						System.out.println(sb.toString());
						br.close();
						response = new JSONObject(sb.toString());
					}
				} catch (IOException ex) {
					System.out.println("IO Exception ");
				}
				if (response != null) {
					try {
						String sentiment = response.getJSONObject(
								"docSentiment").getString("type");
						jsonObject.put("sentiment", sentiment);
						String topicArn = "arn:aws:sns:us-west-2:908762746590:tweets";
						AmazonSNSClient snsClient = new AmazonSNSClient(
								credentials);
						snsClient
								.setRegion(Region.getRegion(Regions.US_WEST_2));
						PublishRequest publishRequest = new PublishRequest(
								topicArn, jsonObject.toString());
						PublishResult publishResult = snsClient
								.publish(publishRequest);
						System.out.println(publishResult.toString());
					} catch (JSONException e) {
						System.out.println("Skipped Tweet");
						e.printStackTrace();
					} catch (Exception e) {
						// TODO: handle exception
						e.printStackTrace();
					}
				} else {
					System.out.println("response null");
				}
			}
		}
	}
}
