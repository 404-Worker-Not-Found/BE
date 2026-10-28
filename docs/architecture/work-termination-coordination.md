# 근무 종료 전파와 MVP 결제 잠금 해제 (#104, #105)

## 범위

PR #131의 WorkCompleted(3), WorkNoShow(4), WorkCanceled(5) v1을 소비한다. 완료는 매칭 COMPLETED, 취소·노쇼는 매칭/선정 지원 CANCELED로 반영한다. 취소·노쇼의 정상 예치 잠금만 전액 해제하고 PG 환불은 실행하지 않는다. 사용자의 2026-10-28 MVP 우선 지시에 따라 패널티·자동 재모집·실제 지급은 제외한다. [출시 보강 목록](../mvp-release-gaps.md)을 따른다.

## 원자성과 이벤트 순서

- matching-service는 기존 application → matching → Saga 잠금 순서로 확정된 work ID와 참여자를 대조하고 상태·이력·지원 Outbox·수신 기록을 함께 저장한다. 과거 보상 시도의 work는 현재 확정 매칭을 종료하지 않는다.
- payment-service는 전용 소비 그룹으로 취소·노쇼를 처음부터 읽는다. MatchConfirmed 정산 대상이 먼저 오지 않았으면 ACK하지 않고 pending으로 재시도한다.
- 결제 잠금 순서는 기존처럼 matching slot → deposit → payment lock이다. 종료 결과·잠금 해제·금액 이동·이력·수신 기록은 한 트랜잭션이다.
- eventId 수신 기록과 별개로 work별 종료 사실을 유일하게 보관한다. 같은 사실의 새 eventId는 반복 효과를 내지 않고, 다른 종료 사실·다른 work/참여자·다른 원격 자원은 거절 기록을 남긴다.
- WorkCompleted가 취소·노쇼 이후 도착해도 이미 해제된 금액을 정산으로 가져가지 않는다. 반대 순서로 정산이 먼저 생겼다면 잠금은 풀지 않고 지급 가능한 정산을 보류한다.

## MVP 처리 결과

| 조건 | 처리 |
| --- | --- |
| 정상 확정 대상·활성 LOCKED·정상 예치 | RELEASED, 해당 금액만 가용 잔액으로 복귀 |
| 같은 대상이 이미 RELEASED | 반복 성공, 추가 금액 변경 없음 |
| 예치 차단 | 검토 대상으로 저장, 잠금 유지 |
| 잠금이 정산으로 TRANSFERRED | 검토 대상으로 저장, 미지급 정산은 가능한 상태에서 보류 |
| 지급 결과 미확정/이미 지급 | 자동 회수·해제하지 않고 검토 필요 |
| 새 Saga/다른 확정 work 또는 참여자 불일치 | 거절 기록, 다른 자원 변경 없음 |
| 확정 대상 미도착·DB 장애·스키마 해석 불가 | pending, 재시도 |

완료된 근무의 선정 지원은 SELECTED 사실을 유지하고 매칭만 COMPLETED로 끝낸다. 취소·노쇼는 선정 지원을 CANCELED로 바꾸며 원인을 이력에 남긴다. 기존 APPLIED 지원 취소 API의 마감·권한 규칙을 우회하는 공개 API는 추가하지 않는다.

## 소비자 설정

결제 종료 소비자는 `PAYMENT_SETTLEMENT_EVENTS_ENABLED`와 `PAYMENT_WORK_TERMINATION_ENABLED`가 모두 켜졌을 때 활성화한다(기본 둘 다 true). 어느 하나를 끄면 생성하지 않는다. 정산 이벤트를 끈 상태에서만 등록되는 설정 빈을 참조해 애플리케이션 시작이 실패하지 않도록 같은 의존 조건을 적용한다.
