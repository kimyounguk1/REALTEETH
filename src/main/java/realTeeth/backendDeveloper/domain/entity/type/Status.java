package realTeeth.backendDeveloper.domain.entity.type;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum Status {

    PENDING("백엔드 서버 처리 대기중"),
    PROCESSING("API-KEY 발급 완료"),
    COMPLETED("AI 서버에 요청 완료"),
    FAILED("실패");

    private final String description;

}
