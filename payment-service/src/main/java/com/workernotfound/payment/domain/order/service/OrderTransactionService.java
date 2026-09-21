package com.workernotfound.payment.domain.order.service;

import com.workernotfound.payment.domain.order.dto.*;
import com.workernotfound.payment.domain.order.exception.OrderErrorCode;
import com.workernotfound.payment.domain.order.repository.PaymentOrderRepository;
import com.workernotfound.payment.domain.order.repository.PaymentOrderRepository.Order;
import com.workernotfound.payment.external.toss.TossPayment;
import com.workernotfound.payment.global.exception.BusinessException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderTransactionService {
  private final PaymentOrderRepository orders;

  public record Claim(Order order, String token, boolean firstAttempt) {}

  @Transactional
  public OrderResponse create(String key, CreateOrderRequest request) {
    var command = orders.lockCommand(key, request.fingerprint());
    if (!command.fingerprint().equals(request.fingerprint())) throw conflict();
    if (command.orderId() != null) return OrderResponse.from(require(command.orderId()));
    String activeId = orders.lockJob(request.jobPostId());
    if (activeId != null) supersede(activeId, request);
    String id = UUID.randomUUID().toString();
    orders.insert(id, key, request);
    return OrderResponse.from(require(id));
  }

  private void supersede(String activeId, CreateOrderRequest r) {
    Order active = require(activeId);
    if (!active.ownerId().equals(r.ownerMemberId()) || r.jobVersion() < active.jobVersion())
      throw conflict();
    if (!Set.of("READY", "FAILED").contains(active.status())) throw conflict();
    boolean sameVersion = r.jobVersion().equals(active.jobVersion());
    if (sameVersion
        && (active.amount().compareTo(r.amount()) != 0 || !active.currency().equals(r.currency())))
      throw conflict();
    if (sameVersion && active.status().equals("READY")) throw conflict();
    orders.transition(active.id(), "SUPERSEDED");
  }

  @Transactional(readOnly = true)
  public OrderResponse getOrder(String id, Long ownerId) {
    Order order = orders.findById(id).orElseThrow(this::notFound);
    if (!order.ownerId().equals(ownerId)) throw notFound();
    return OrderResponse.from(order);
  }

  @Transactional
  public Claim prepare(String id, Long ownerId, ConfirmOrderRequest request) {
    Order order = require(id);
    if (!order.ownerId().equals(ownerId)) throw notFound();
    if (order.amount().compareTo(request.amount()) != 0) throw conflict();
    if (order.paymentKey() != null && !order.paymentKey().equals(request.paymentKey()))
      throw conflict();
    if (order.status().equals("DEPOSITED")) return null;
    if (!Set.of("READY", "CONFIRMING").contains(order.status())) throw conflict();
    if (orders.activeLease(order)) throw new BusinessException(OrderErrorCode.BUSY);
    boolean first = order.status().equals("READY");
    if (first) orders.bind(id, request.paymentKey());
    return lease(require(id), first);
  }

  @Transactional
  public Claim claimRecovery(String id) {
    Order order = require(id);
    if (order.paymentKey() == null
        || !Set.of("CONFIRMING", "DEPOSITED").contains(order.status())
        || orders.activeLease(order)) return null;
    return lease(order, false);
  }

  private Claim lease(Order order, boolean first) {
    String token = UUID.randomUUID().toString();
    orders.claim(order.id(), token);
    return new Claim(order, token, first);
  }

  @Transactional
  public void finish(Claim claim, TossPayment payment) {
    Order current = require(claim.order().id());
    if (!ownsLease(current, claim)) return;
    verifyIdentity(current, payment);
    boolean retry = applyPayment(current, payment);
    // Use the revision captured before network IO so a concurrent webhook is not lost.
    orders.release(claim.order(), retry);
  }

  private boolean applyPayment(Order order, TossPayment payment) {
    if ("DONE".equals(payment.status())) {
      if (!"카드".equals(payment.method())
          || payment.approvedAt() == null
          || payment.balanceAmount() == null
          || payment.balanceAmount().compareTo(order.amount()) != 0) {
        orders.block(order);
      } else if (!"DEPOSITED".equals(order.status())) {
        orders.credit(
            order,
            payment.approvedAt().withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime());
      }
      return false;
    }
    if (Set.of("CANCELED", "PARTIAL_CANCELED").contains(payment.status())) {
      if ("DEPOSITED".equals(order.status()) || "PARTIAL_CANCELED".equals(payment.status()))
        orders.block(order);
      else orders.transition(order.id(), "FAILED");
      return false;
    }
    if (Set.of("ABORTED", "EXPIRED").contains(payment.status())
        && !"DEPOSITED".equals(order.status())) {
      orders.transition(order.id(), "FAILED");
      return false;
    }
    return true;
  }

  public void verifyIdentity(Order order, TossPayment payment) {
    if (payment == null
        || !order.id().equals(payment.orderId())
        || !order.paymentKey().equals(payment.paymentKey())
        || !order.currency().equals(payment.currency())
        || payment.totalAmount() == null
        || order.amount().compareTo(payment.totalAmount()) != 0
        || payment.status() == null) throw new BusinessException(OrderErrorCode.PROVIDER_MISMATCH);
  }

  @Transactional
  public void retry(Claim claim) {
    Order current = require(claim.order().id());
    if (ownsLease(current, claim)) orders.release(claim.order(), true);
  }

  private boolean ownsLease(Order current, Claim claim) {
    return claim.token().equals(current.leaseToken()) && orders.activeLease(current);
  }

  private Order require(String id) {
    return orders.findByIdForUpdate(id).orElseThrow(this::notFound);
  }

  private BusinessException notFound() {
    return new BusinessException(OrderErrorCode.NOT_FOUND);
  }

  private BusinessException conflict() {
    return new BusinessException(OrderErrorCode.CONFLICT);
  }
}
