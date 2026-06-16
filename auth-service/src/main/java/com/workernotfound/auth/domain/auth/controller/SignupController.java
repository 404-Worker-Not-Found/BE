package com.workernotfound.auth.domain.auth.controller;

import com.workernotfound.auth.domain.auth.controller.docs.SignupControllerDocs;
import com.workernotfound.auth.domain.auth.dto.request.OwnerSignupRequest;
import com.workernotfound.auth.domain.auth.dto.request.WorkerSignupRequest;
import com.workernotfound.auth.domain.auth.dto.response.SignupResponse;
import com.workernotfound.auth.domain.auth.service.SignupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/signup")
public class SignupController implements SignupControllerDocs {

	private final SignupService signupService;

	@Override
	@PostMapping("/owner")
	public ResponseEntity<SignupResponse> signupOwner(
		@Valid @RequestBody OwnerSignupRequest request
	) {
		SignupResponse response = signupService.signupOwner(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@Override
	@PostMapping("/worker")
	public ResponseEntity<SignupResponse> signupWorker(
		@Valid @RequestBody WorkerSignupRequest request
	) {
		SignupResponse response = signupService.signupWorker(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
