# REALTEETH

# 비동기 이미지 처리 시스템 설계

## 1. 과제 개요 및 핵심 설계 의도
본 과제는 '불확실성이 높은 외부 AI 서비스(Mock Worker)와의 연동 시, 시스템의 안정성과 데이터 정합성을 어떻게 유지할 것인가?'에 대한 고민을 바탕으로 설계되었습니다. 대량의 트래픽과 외부 서버의 지연/장애 상황에서도 시스템이 예측 가능하고 내구성 있게 동작하도록 다음의 핵심 기술을 도입했습니다.

* Java 21 Virtual Threads: I/O 바운드 작업(외부 API 호출 및 대기)이 주를 이루는 시스템 특성상, 가상 스레드를 활용해 적은 리소스로 높은 동시 처리량을 확보했습니다.
* Apache Kafka 기반 비동기 큐잉: 무거운 이미지 처리 요청을 API Server가 직접 동기적으로 기다리지 않고, Kafka를 통해 비동기로 위임하여 서버의 응답성을 높이고 요청 유실을 방지합니다.
* 독립적인 상태 관리 (Isolated State Management): 외부 API 호출(I/O) 같은 긴 작업과 DB 트랜잭션을 분리했습니다. 상태 업데이트 로직을 `TaskStatusService`로 분리하여 짧은 트랜잭션(`REQUIRES_NEW`)을 유지함으로써, 애플리케이션 내부 예외나 API 통신 실패 시에도 부모 트랜잭션에 롤백되지 않고 작업의 최종 상태가 DB에 안전하게 커밋되도록 설계했습니다.

flowchart LR
    Client([Client]) -- 1. 이미지 처리 요청 --> API_Server[API Server\n(Virtual Threads)]
    
    subgraph "Backend System"
        API_Server -- 2. 상태 저장 (PENDING) --> DB[(Database)]
        API_Server -- 3. 메시지 발행 (Throttling 버퍼) --> Kafka{Apache Kafka\n(Topic: image.send)}
        
        Kafka -- 4. 메시지 소비 (Backpressure) --> Consumer[Kafka Listener\n(Consumer)]
        Consumer -- 6. 상태 업데이트\n(COMPLETED / FAILED) --> DB
        
        Scheduler((Recovery\nScheduler)) -. 7. 1시간 주기 스캔\n(Zombie Task 정리) .-> DB
    end

    API_Server -. Issue Key 발급 .-> Mock_Worker[External System\nMock Worker]
    Consumer -- 5. AI 이미지 처리 요청 --> Mock_Worker
    
---

## 2. 상태 모델 설계 (State Machine)
작업의 생명주기를 4단계로 정의하여 불확실성을 통제합니다.

* `PENDING` (백엔드 서버 처리 대기중): 클라이언트 요청이 접수되어 DB에 초기 저장된 상태.
* `PROCESSING` (API-KEY 발급 완료): 인증 키가 발급되고, Kafka로 메시지가 발행되어 AI 서버로 요청이 진행 중인 상태.
* `COMPLETED` (AI 서버에 요청 완료): 외부 서버(Mock Worker)로부터 정상적으로 `jobId`를 발급받은 최종 성공 상태.
* `FAILED` (실패): 네트워크 타임아웃, 커넥션 거부, 시스템 내부 오류, 혹은 장기 미처리(좀비 상태)로 인한 작업 실패 상태.

[상태 전이 흐름 및 제한]
* 정상 흐름: `PENDING` ➔ `PROCESSING` ➔ `COMPLETED`
* 실패 흐름: `PENDING` ➔ `FAILED` 또는 `PROCESSING` ➔ `FAILED`
* 제한: 한 번 종료된 상태(`COMPLETED`, `FAILED`)에서 다른 상태로의 전이는 허용하지 않으며, 처리 과정의 명확성을 위해 외부 API 호출 실패 시 섣부른 재시도를 하지 않고 즉시 `FAILED` 처리합니다.

---

## 3. 주요 요구사항 해결 방식

### 3.1 처리 보장 모델 (Delivery Semantics)
본 시스템은 외부 AI 서버 연동에 있어 [최대 한 번 전달 (At-Most-Once)] 모델을 기반으로 한 명시적 실패(Fail-Fast) 전략을 채택했습니다.
* 판단 근거: AI 이미지 처리는 GPU 리소스를 크게 소모하는 무거운 작업입니다. 만약 네트워크 타임아웃 시 시스템이 자동으로 재시도(Retry)를 수행한다면, 실제로는 AI 서버가 작업을 수행 중임에도 불구하고 동일한 요청이 중복으로 전달되어 외부 시스템에 치명적인 부하를 유발할 수 있습니다.
* 해결 방식: 카프카 컨슈머에서 타임아웃 등 예외가 발생하더라도 재처리하지 않고 해당 작업을 즉시 `FAILED` 상태로 마킹합니다. 불확실한 재시도보다 클라이언트에게 명확한 실패를 알리는 것이 시스템 전체의 안정성을 위해 적합하다고 판단했습니다.

### 3.2 중복 요청 및 동시성 제어
* 동일한 클라이언트(email)가 동일한 이미지(imageUrl)를 중복해서 요청하는 것을 방지하기 위해 `duplicateCheck` 로직을 도입했습니다.
* 현재 진행 중인 작업(`PENDING`, `PROCESSING` 상태)이 존재할 경우 `CustomException(DUPLICATE_REQUEST)`을 발생시켜 불필요한 리소스 낭비와 외부 API 중복 호출을 원천 차단합니다.

### 3.3 서버 재시작 시 동작 및 데이터 정합성 보장
* Kafka를 통한 요청 유실 방지: 어플리케이션 서버가 예기치 않게 종료되더라도, Kafka 큐에 발행된 메시지는 컨슈머가 정상 처리를 완료(Offset Commit)하기 전까지 브로커에 안전하게 영속화됩니다. 따라서 서버 재시작 시 Kafka Consumer가 이어서 메시지를 소비하므로 요청 자체의 소실을 막을 수 있습니다.
* 상태 고립(Zombie Task) 해결: 처리 도중 서버가 종료되어 가상 스레드 메모리 작업은 중단되었으나 DB에는 `PROCESSING` 상태로 고립된 데이터가 발생할 수 있습니다. 이를 복구하기 위해 `TaskRecoveryScheduler`가 동작합니다. 생성된 지 10분 이상(`minusMinutes(10)` 기준) 지났음에도 완료되지 않은 작업들을 주기적으로 스캔하여 일괄적으로 `FAILED` 처리함으로써 데이터 정합성을 일관되게 복구합니다. (상태 및 생성 시간 인덱싱 처리를 통해 조회 성능 향상 고려)

### 3.4 트래픽 증가 시 병목 가능 지점 및 시스템 보호 전략
* 병목 현상 (Consumer Lag): 가상 스레드를 활용한 API 서버의 요청 수용 속도(Producer)가 AI 서버의 처리 속도(Consumer)를 압도할 경우, Kafka 브로커 자체가 다운되지는 않으나 큐에 메시지가 누적되는 Consumer Lag(컨슈머 랙) 증가에 따른 논리적 병목(클라이언트의 무한 대기) 현상이 발생할 수 있습니다.
* 대응 및 보호 아키텍처: 
  1. 기본적으로 Kafka를 Throttling 버퍼로 활용하여 외부 AI 서버로 향하는 초당 요청수(RPS)를 제어(Backpressure)합니다.
  2. [향후 운영 개선] Spring Boot Actuator/Micrometer 또는 Kafka Exporter를 통해 Consumer Lag 지표를 Prometheus와 Grafana로 모니터링합니다. Lag이 시스템 허용 임계치를 초과할 경우, API 앞단(Gateway 또는 Interceptor)에서 신규 요청을 `429 Too Many Requests`로 선제 차단(Rate Limiting)하여 큐의 마비와 시스템 연쇄 장애를 방어하는 아키텍처를 고려했습니다.

---

## 4. 한계점 및 향후 개선 제안 (Future Improvements)
현재의 아키텍처에서 한 단계 더 나아간 실제 운영 환경이라면 다음과 같은 개선을 제안합니다.

1. 멱등성 키(Idempotency Key) 협의: 현재의 '최대 한 번 전달' 제약을 극복하기 위해, 외부 서버 API 스펙에 멱등성 키 헤더를 추가하도록 협의할 수 있습니다. 이를 통해 네트워크 단절 시에도 외부 서버에서 알아서 중복 처리를 방지해 주므로 안전하게 재시도(Retry)를 수행할 수 있습니다.
2. 콜백(Webhook) 기반 비동기 아키텍처: 현재 클라이언트가 작업 상태를 확인하기 위해 지속적으로 폴링(Polling)을 해야 합니다. AI 서버의 작업이 수십 초 이상 걸릴 수 있으므로, 완료 시점에 외부 서버가 우리 서버의 특정 엔드포인트(Webhook)로 결과를 전달해 주는 이벤트 기반 구조로 개선하면 서버와 네트워크 리소스를 훨씬 더 효율적으로 사용할 수 있습니다.

---

## 5. 실행 방법 (How to Run)

### Prerequisites
* Java 21
* Docker & Docker Compose (Kafka, Database 등 인프라 실행용)

### Execution Steps
1. 인프라 컨테이너 실행 (Kafka, DB 등)
   ```bash
   docker-compose up -d
