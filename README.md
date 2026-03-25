# REALTEETH: Transactional Outbox 기반 비동기 이미지 처리 시스템

## 1. 설계 의도: 예외 상황의 완전 통제
외부 AI 서비스와 연동하는 비동기 시스템에서 보상 로직(Saga)조차 실패하여 발생 가능한 모든 실패 지점을 정의하고, 이를 자가 치유(Self-healing)할 수 있는 구조를 설계하는 데 집중했습니다.

## 2. 시스템 아키텍처 및 트랜잭션 흐름
시스템의 흐름은 아래와 같이 세 단계의 격리된 트랜잭션으로 운영됩니다.

```
flowchart TD
    subgraph Client_Zone [Client Interface]
        User([사용자]) -- 1. 분석 요청 --> API[API Server]
    end

    subgraph DB_Zone [Persistence Layer]
        Task[(Task Table)]
        Outbox[(Outbox Table)]
    end

    subgraph Relay_Zone [Message Relay]
        Relay[[Outbox Relay Scheduler]]
        Relay -- "2. Pre-marking (REQUIRES_NEW)" --> DB_Zone
        Relay -- "3. Synchronous Send (get)" --> Kafka{Kafka Topic}
    end

    subgraph Execution_Zone [AI Processing]
        Kafka -- 4. Consume --> Worker[Kafka Listener]
        Worker -- 5. External API Call --> AI[AI Mock Worker]
        Worker -- 6. State Update --> Task
    end

    subgraph Recovery_Zone [Self Healing]
        RS[[Recovery Scheduler]] -- "7. Zombie Scan (10m)" --> Task
    end

    API -.->|PENDING| Task
    Relay -.->|PROCESSING| Task
    Worker -.->|SUCCESS/FAILED| Task
```

## 3. 좀비 태스크 시나리오 및 수습 전략
로직 실패 후 실행되는 보상 로직(Saga)이 네트워크나 DB 장애로 인해 다시 실패할 때 발생하는 4가지 고립 상황을 정의했습니다.

### 시나리오 1: 초기 저장 단계의 고립 (PENDING 좀비)
- 상황: Task(PENDING) 저장 직후 Outbox 저장 실패 및 보상 로직(updateToFailed) 실패.
- 결과: 발송 대기 상태로 DB에 남았으나 발송 장부(Outbox)가 없어 Relay가 인지할 수 없는 상태로 방치됨.
- 수습: Recovery Scheduler가 생성된 지 10분이 지난 PENDING 작업을 탐지하여 일괄 FAILED 처리.

### 시나리오 2: 발송 단계의 고립 (PROCESSING 좀비)
- 상황: markAsAttempted(PROCESSING) 성공 후 Kafka 전송 실패 및 보상 로직 실패.
- 결과: 장부에는 '전송됨'으로 기록되어 Relay가 다시 잡지 않으나, 실제로는 메시지가 유실되어 PROCESSING 상태로 고립됨.
- 수습: Recovery Scheduler가 수정된 지 10분이 지난 PROCESSING 작업을 탐지하여 일괄 FAILED 처리.

### 시나리오 3 & 4: 응답 반영 단계의 고립 (PROCESSING 좀비)
- 상황: AI 서버 작업이 성공 혹은 실패했으나, 그 결과를 DB에 업데이트하는 과정(Success/Fail Update)에서 장애 발생.
- 결과: 외부 서버 작업은 물리적으로 종료되었으나 우리 서버 장부상으로는 여전히 '분석 중'으로 고립됨.
- 수습: Recovery Scheduler가 수정된 지 10분이 지난 PROCESSING 작업을 탐지하여 일괄 FAILED 처리.

## 4. 핵심 기술적 장치

### 4.1 REQUIRES_NEW를 이용한 Pre-marking
Outbox Relay는 Kafka에 메시지를 쓰기 전, 반드시 별도의 독립 트랜잭션을 열어 Task의 status를 PROCESSING으로 수정합니다. 
- 이유: 전송 후 상태를 바꾸면, 전송 성공 직후 서버가 죽었을 때 재시작 시 중복 메시지가 발행될 위험이 있습니다. 
- 해결: 먼저 장부에 전송 중임을 확정(Commit) 지음으로써 중복 발송 가능성을 100% 차단합니다.

### 4.2 동기식 전송 확인 (Sync Send)
`kafkaTemplate.send().get()`을 사용하여 브로커가 메시지를 확실히 수신했는지 확인합니다. 비동기 전송의 함정인 전송 결과 누락을 방지하고, 실패 시 즉시 catch 블록을 통해 보상 로직을 가동합니다.

### 4.3 가상 스레드(Virtual Threads) 기반 I/O 처리
Java 21의 가상 스레드를 활용하여 Outbox Relay의 DB 조회 및 Kafka 전송, Listener의 외부 API 호출 시 발생하는 블로킹 타임을 최소한의 리소스로 소화합니다. 

## 5. 설계 결론: 명확한 실패(Fail-Fast)
모호한 재시도(Retry)는 시스템 부하와 데이터 복잡도를 가중시킵니다. 본 설계는 실패를 처리하는 로직조차 실패한다면, 이를 억지로 다시 실행하기보다 타임아웃 기반의 스케줄러를 통해 상황을 조기에 종결시키는 Fail-Fast 전략을 택했습니다. 이를 통해 사용자는 10분 이내에 확정된 실패 응답을 받고 재요청할 수 있는 상태를 보장받습니다.
