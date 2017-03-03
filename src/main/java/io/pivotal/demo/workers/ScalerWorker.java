package io.pivotal.demo.workers;

import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;

import io.pivotal.demo.domain.Rule;
import io.pivotal.demo.domain.ServiceBinding;
import io.pivotal.demo.domain.ServiceInstance;
import io.pivotal.demo.helper.Connection;
import io.pivotal.demo.repository.RuleRepository;
import io.pivotal.demo.repository.ServiceBindingRepository;
import io.pivotal.demo.repository.ServiceInstanceRepository;

@Component
public class ScalerWorker {

	@Autowired
	RuleRepository ruleRepository;

	@Autowired
	ServiceInstanceRepository serviceInstanceRepository;

	@Autowired
	ServiceBindingRepository serviceBindingRepository;

	private String LOGIN_HOST = "";
	private String API_HOST = "";

	private static final Logger log = LoggerFactory.getLogger(ScalerWorker.class);
	private static final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");

	private static TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
		public java.security.cert.X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}

		public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
		}

		public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
		}
	} };

	@Scheduled(fixedRate = 30000)
	public void appScaler() {

		log.info(dateFormat.format(new Date()));

		Iterable<ServiceBinding> bindings = serviceBindingRepository.findAll();
		Iterable<ServiceInstance> instances = serviceInstanceRepository.findAll();
		log.debug("bindings=" + bindings);
		log.debug("instances=" + instances);

		this.API_HOST = System.getenv("CC_HOST");
		this.LOGIN_HOST = System.getenv("LOGIN_HOST");
		log.debug("LOGIN_HOST=" + LOGIN_HOST);
		log.debug("API_HOST=" + API_HOST);

		log.info("number of service bindings=" + bindings.spliterator().getExactSizeIfKnown());
		for (ServiceBinding binding : bindings) {
			log.debug("binding=" + binding.toString());
			log.debug("appGUID=" + binding.getAppGUID());
			List<Rule> rules = ruleRepository.findByAppGUID(binding.getAppGUID());
			log.info("number of rules=" + rules.size());

			for (Rule rule : rules) {
				String ruleExpression = rule.getRuleExpression();
				log.info("app ruleExpression=" + ruleExpression);

				JSONParser parser = new JSONParser();
				JSONObject jsonRules = null;

				try {
					jsonRules = (org.json.simple.JSONObject) parser.parse(ruleExpression);
				} catch (ParseException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}

				log.debug("JSON app rule=" + jsonRules.toJSONString());

				// We only deal with one rule at this time
				JSONArray a = (JSONArray) jsonRules.get("rules");
				JSONObject jsonRule = (JSONObject) a.get(0);

				// Rule info
				String dataName = (String) jsonRule.get("field");
				String ruleCompareValue = (String) jsonRule.get("value");
				String operator = (String) jsonRule.get("operator");
				String ruleOperator = getOperator(operator);

				log.info("JSON rule[0] dataName=" + dataName + " ruleCompareValue=" + ruleCompareValue
						+ " operator conversion=" + ruleOperator);

				String appAPI = this.API_HOST + "/v2/apps/" + rule.getAppGUID();
				log.debug("appAPI=" + appAPI);

				JSONObject appSummary = getCurrentInstances(appAPI);

				JSONObject r = getData(rule.getRuleURL());
				log.info("return of rule url=" + rule.getRuleURL() + " response=" + r);

				boolean scaleApp = compareValues(ruleCompareValue, (Double) r.get(dataName), ruleOperator);
				log.info("scale app=" + scaleApp);

				Long appInstanceCount = (Long) appSummary.get("instances");
				log.info("app summary number of instances=" + appInstanceCount);
				Long minInstances = Long.parseLong(rule.getMinInstances());
				Long maxInstances = Long.parseLong(rule.getMaxInstances());

				if (scaleApp) {
					if (appInstanceCount < maxInstances) {
						appInstanceCount = appInstanceCount + 1;
						log.info("scale the app up=" + appInstanceCount);
						scaleApp(appAPI, appInstanceCount);
					}
				} else {
					// check to see if we need to scale down.....
					if (appInstanceCount > minInstances) {
						appInstanceCount = appInstanceCount - 1;
						log.info("scale the app down=" + appInstanceCount);
						scaleApp(appAPI, appInstanceCount);
					}
					// check to see if we need to scale to
					// minimum.....
					else if (appInstanceCount < minInstances) {
						appInstanceCount = appInstanceCount + 1;
						log.info("scale the app to mininum=" + appInstanceCount);
						scaleApp(appAPI, appInstanceCount);
					}
				}
			}

		}
	}

	private boolean compareValues(String ruleCompareValue, Double metricValue, String operator) {
		if (operator.equals("=")) {
			if (Double.valueOf(ruleCompareValue) == metricValue) {
				return true;
			}
		} else if (operator.equals(">")) {
			if (metricValue > Double.valueOf(ruleCompareValue)) {
				return true;
			}
		} else if (operator.equals("<")) {
			if (metricValue < Double.valueOf(ruleCompareValue)) {
				return true;
			}
		}

		return false;
	}

	// get data for rule url
	private JSONObject getData(String url) {
		RestTemplate restTemplate = new RestTemplate();
		ResponseEntity<String> responseEntity;
		JSONParser parser = new JSONParser();
		JSONObject json = null;

		HttpHeaders headers = new HttpHeaders();
		headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));

		LinkedMultiValueMap<String, String> postBody = new LinkedMultiValueMap<>();

		// get data for rule url
		responseEntity = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(postBody, headers), String.class);

		try {
			json = (org.json.simple.JSONObject) parser.parse(responseEntity.getBody());
		} catch (ParseException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

		return json;
	}

	public JSONObject scaleApp(String scaleAppURL, Long currentInstances) {
		RestTemplate restTemplate = new RestTemplate();
		ResponseEntity<String> responseEntity;
		JSONParser parser = new JSONParser();
		JSONObject json = null;

		HttpHeaders headers = Connection.getAuthorizationHeader(getOAuthToken(), API_HOST);
		headers.add("Content-Type", "application/json");

		JSONObject postBody = new JSONObject();
		postBody.put("instances", currentInstances);

		// System.out.println("scaleApp headers=" + headers);
		log.debug("scaleApp scaleAppURL=" + scaleAppURL);
		log.debug("scaleApp postBody=" + postBody);

		// get current instances
		responseEntity = restTemplate.exchange(scaleAppURL, HttpMethod.PUT,
				new HttpEntity<>(postBody.toJSONString(), headers), String.class);
		log.debug("scaleApp return=" + responseEntity.getBody() + " status" + responseEntity.getStatusCodeValue());

		try {
			json = (org.json.simple.JSONObject) parser.parse(responseEntity.getBody());
		} catch (ParseException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

		return json;
	}

	public JSONObject getCurrentInstances(String scaleAppURL) {
		RestTemplate restTemplate = new RestTemplate();
		ResponseEntity<String> responseEntity;
		JSONParser parser = new JSONParser();
		JSONObject json = null;

		HttpHeaders headers = Connection.getAuthorizationHeader(getOAuthToken(), API_HOST);

		// get current instances
		responseEntity = restTemplate.exchange(scaleAppURL + "/summary", HttpMethod.GET,
				new HttpEntity<>(null, headers), String.class);

		try {
			json = (org.json.simple.JSONObject) parser.parse(responseEntity.getBody());
		} catch (ParseException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

		return json;
	}

	private String getOAuthToken() {
		// Install the all-trusting trust manager
		try {
			SSLContext sc = SSLContext.getInstance("SSL");
			sc.init(null, trustAllCerts, new java.security.SecureRandom());
			HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
		} catch (GeneralSecurityException e) {
			e.printStackTrace();
		}

		RestTemplate restTemplate = new RestTemplate();

		HttpHeaders headers = Connection.getBasicAuthorizationHeader("cf", "");

		LinkedMultiValueMap<String, String> postBody = new LinkedMultiValueMap<>();
		postBody.add("grant_type", "password");
		postBody.add("username", System.getenv("CF_ADMIN_USER"));
		postBody.add("password", System.getenv("CF_ADMIN_PASSWORD"));

		ResponseEntity<String> r = restTemplate.exchange(LOGIN_HOST + "/oauth/token", HttpMethod.POST,
				new HttpEntity<>(postBody, headers), String.class);

		org.json.simple.parser.JSONParser parser = new org.json.simple.parser.JSONParser();
		org.json.simple.JSONObject json = null;
		try {
			json = (org.json.simple.JSONObject) parser.parse(r.getBody());
		} catch (ParseException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

		return (String) json.get("access_token");

	}

	private String getOperator(String englishValue) {
		if (englishValue.equals("equal")) {
			return "=";
		} else if (englishValue.equals("greater")) {
			return ">";
		} else if (englishValue.equals("less")) {
			return "<";
		} else if (englishValue.equals("greater or equal")) {
			return ">=";
		} else if (englishValue.equals("less or equal")) {
			return "<=";
		}

		return "=";
	}
}
