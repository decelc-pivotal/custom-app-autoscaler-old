package io.pivotal.demo.controllers;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import io.pivotal.demo.domain.Rule;
import io.pivotal.demo.repository.RuleRepository;

@RestController
public class AppAutoscalerController {

	@Autowired
	MailSender mailSender;

	@Autowired
	RuleRepository ruleRepository;

	@PostMapping("/rules")
	public ResponseEntity<?> add(@RequestBody Rule rule) {

		List<Rule> rules = ruleRepository.findByAppGUID(rule.getAppGUID());

		String id = "";
		Rule savedRule;

		if (rules.size() == 0) {

			Rule _rule = ruleRepository.save(rule);
			assert _rule != null;
			id = _rule.getId();
			savedRule = _rule;
		} else {
			Rule r = rules.get(0);
			savedRule = rule;
			id = r.getId();
		}

		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders
				.setLocation(ServletUriComponentsBuilder.fromCurrentRequest().path("/" + id).buildAndExpand().toUri());

		return new ResponseEntity<>(savedRule, httpHeaders, HttpStatus.CREATED);
	}

	@GetMapping("/rules/{id}")
	public Rule snippet(@PathVariable("id") String id) {
		return ruleRepository.findOne(id);
	}

	@GetMapping("/rules")
	public Iterable<Rule> rules() {
		return ruleRepository.findAll();
	}

	@RequestMapping("/email")
	public String email() {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setText("Hello from Spring Boot Application");
		message.setTo("email@domain.com");
		message.setFrom("email@domain.com");
		try {

			mailSender.send(message);
			return "{\"message\": \"OK\"}";
		} catch (Exception e) {
			e.printStackTrace();
			return "{\"message\": \"Error\"}";
		}

	}
}
