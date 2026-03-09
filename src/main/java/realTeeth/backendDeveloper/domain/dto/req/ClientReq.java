package realTeeth.backendDeveloper.domain.dto.req;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ClientReq {

    @NotBlank(message = "이름은 빈값이 올 수 없습니다.")
    private String candidateName;

    @Email(message = "이메일 형식이 잘못됐습니다.")
    private String email;

    @NotBlank(message = "이미지 url은 빈값이 올 수 없습니다.")
    private String imageUrl;

}
