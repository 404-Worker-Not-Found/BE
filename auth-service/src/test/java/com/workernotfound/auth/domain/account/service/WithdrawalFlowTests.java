package com.workernotfound.auth.domain.account.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.workernotfound.auth.domain.account.entity.*;
import com.workernotfound.auth.domain.account.entity.enums.*;
import com.workernotfound.auth.domain.account.repository.*;
import com.workernotfound.auth.domain.token.repository.RefreshTokenRepository;
import com.workernotfound.auth.domain.token.service.*;
import com.workernotfound.auth.support.IntegrationTestSupport;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@AutoConfigureMockMvc
@TestPropertySource(properties="auth.withdrawal.retry-delay-ms=3600000")
class WithdrawalFlowTests extends IntegrationTestSupport {
    @Autowired WithdrawalTransactionService transactions;
    @Autowired AuthAccountRepository accounts;
    @Autowired TokenService tokens;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    String issuedAccessToken;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    HttpServer server;
    WithdrawalDispatcher dispatcher;
    List<String> actions = new CopyOnWriteArrayList<>();
    volatile boolean reject, loseFirstCommit;

    @BeforeEach
    void startServer() throws Exception {
        reject=false;loseFirstCommit=false;actions.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/", exchange -> {
            var request = mapper.readTree(exchange.getRequestBody());
            String path = exchange.getRequestURI().getPath();
            String action = path.substring(path.lastIndexOf('/')+1);
            actions.add(action);
            String[] pieces = path.split("/");
            if (loseFirstCommit && action.equals("commit")) {
                loseFirstCommit = false; exchange.sendResponseHeaders(503,-1); exchange.close(); return;
            }
            String state = action.equals("prepare") ? (reject && path.contains("payments") ? "REJECTED":"PREPARED")
                    : action.equals("release") ? "RELEASED" : "COMMITTED";
            String body = "{\"success\":true,\"data\":{\"memberId\":"+pieces[pieces.length-2]
                    +",\"commandId\":\""+request.path("commandId").asText()+"\",\"state\":\""+state+"\"}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        dispatcher = new WithdrawalDispatcher(transactions,mapper);
        String url = "http://127.0.0.1:"+server.getAddress().getPort();
        for (String field:List.of("memberUrl","jobUrl","matchingUrl","workUrl","paymentUrl","chatUrl","notificationUrl"))
            ReflectionTestUtils.setField(dispatcher,field,url);
        ReflectionTestUtils.setField(dispatcher,"secret","test-internal-secret");
    }
    @AfterEach void stopServer() { server.stop(0); }
    @Test
    void rejectedWithdrawalCompensatesEveryGateWithoutErasingTheAccount() {
        reject = true;
        var account = account(); var claims = claims(account); String key=UUID.randomUUID().toString();
        transactions.submit(claims,key); dispatcher.dispatch(key);
        assertThat(transactions.get(claims,key).state()).isEqualTo("REJECTED");
        assertThat(accounts.findById(account.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(actions.stream().filter("release"::equals)).hasSize(7);
        assertThat(actions).doesNotContain("commit");
    }
    @Test
    void lostCommitResponseResumesFinalizationAndErasesCredentialsOnlyAfterAcknowledgments() throws Exception {
        var account=account();var claims=claims(account);String key=UUID.randomUUID().toString();
        loseFirstCommit=true;
        transactions.submit(claims,key);dispatcher.dispatch(key);
        assertThat(transactions.get(claims,key).state()).isEqualTo("FINALIZING");
        assertThat(accounts.findById(account.getId()).orElseThrow().getEmail()).isNotNull();
        assertThat(actions).doesNotContain("release");
        jdbc.update("update account_withdrawals set next_attempt_at=CURRENT_TIMESTAMP(6) where command_id=?",key);
        dispatcher.dispatch(key);
        assertThat(transactions.get(claims,key).state()).isEqualTo("SUCCEEDED");
        var erased=accounts.findById(account.getId()).orElseThrow();
        assertThat(erased.getStatus()).isEqualTo(MemberStatus.WITHDRAWN); assertThat(erased.getEmail()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from local_credentials where auth_account_id=?",Long.class,account.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where auth_account_id=?",Long.class,account.getId())).isZero();
        assertThat(actions.stream().filter("prepare"::equals)).hasSize(7);
        mvc.perform(MockMvcRequestBuilders.get("/api/auth/account/withdrawals/"+key)
                .header("Authorization","Bearer "+issuedAccessToken))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized());
    }
    private AuthAccount account() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            var account=accounts.saveAndFlush(AuthAccount.builder().memberId(System.nanoTime()%10000000).email(UUID.randomUUID()+"@example.com")
                .role(MemberRole.WORKER).signupType(SignupType.LOCAL).build());
            jdbc.update("insert into local_credentials(created_at,updated_at,auth_account_id,password_hash,password_changed_at) values(CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),?,'test-hash',CURRENT_TIMESTAMP(6))",account.getId());
            issuedAccessToken=tokens.issue(account,"device").accessToken();return account;
        });
    }
    private AuthTokenClaims claims(AuthAccount account) { return new AuthTokenClaims(account.getId(),account.getMemberId(),account.getRole()); }
}
