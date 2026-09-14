package com.workernotfound.matching.domain.application.event;

public enum ApplicationEventType {
	APPLICATION_SUBMITTED("ApplicationSubmitted"),
	APPLICATION_CANCELED("ApplicationCanceled"),
	APPLICATION_SELECTED("ApplicationSelected");

	private final String value;

	ApplicationEventType(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}
}
