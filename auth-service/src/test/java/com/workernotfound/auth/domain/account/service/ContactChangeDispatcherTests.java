package com.workernotfound.auth.domain.account.service;

import com.workernotfound.auth.domain.account.repository.ContactChangeRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.mockito.Mockito.*;
import org.springframework.http.MediaType;

class ContactChangeDispatcherTests {
    ContactChangeRepository commands = mock(ContactChangeRepository.class);
    ContactChangeTransactionService transactions = mock(ContactChangeTransactionService.class);
    MockRestServiceServer server;
    ContactChangeDispatcher dispatcher;
    final String id = "00000000-0000-0000-0000-000000000091";

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://member.test");
        server = MockRestServiceServer.bindTo(builder).build();
        dispatcher = new ContactChangeDispatcher(commands, new com.fasterxml.jackson.databind.ObjectMapper(), transactions, builder.build());
        when(transactions.claim(id)).thenReturn(Optional.of(new ContactChangeTransactionService.DispatchCommand(
                id, 91L, "EMAIL", "new@example.com")));
    }
    @Test
    void validResponseCompletesTheDurableCommand() {
        server.expect(requestTo("http://member.test/api/members/internal/91/contact"))
                .andExpect(header("Idempotency-Key", id))
                .andRespond(withSuccess(body("91", "true", "true"), MediaType.APPLICATION_JSON));
        dispatcher.dispatch(id);
        verify(transactions).complete(id, false);
        server.verify();
    }
    @Test
    void explicitRecordedRejectionRestoresTheAccount() {
        server.expect(anything()).andRespond(withSuccess(body("91", "true", "false"), MediaType.APPLICATION_JSON));
        dispatcher.dispatch(id);
        verify(transactions).complete(id, true);
    }
    @Test
    void wrongMemberAndNonBooleanAcceptanceRemainPending() {
        server.expect(anything()).andRespond(withSuccess(body("92", "true", "true"), MediaType.APPLICATION_JSON));
        dispatcher.dispatch(id);
        server.reset();
        server.expect(anything()).andRespond(withSuccess(body("91", "true", "\"false\""), MediaType.APPLICATION_JSON));
        dispatcher.dispatch(id);
        verify(transactions, never()).complete(anyString(), anyBoolean());
    }
    @Test
    void serverErrorKeepsUnknownOutcomePending() {
        server.expect(anything()).andRespond(withServerError());
        dispatcher.dispatch(id);
        verify(transactions, never()).complete(anyString(), anyBoolean());
    }
    private String body(String member, String success, String accepted) {
        return "{\"success\":" + success + ",\"data\":{\"commandId\":\"" + id
                + "\",\"memberId\":" + member + ",\"channel\":\"EMAIL\",\"target\":\"new@example.com\",\"accepted\":" + accepted + "}}";
    }
}
