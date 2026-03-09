package realTeeth.backendDeveloper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KyuApplication {

	public static void main(String[] args) {
		SpringApplication.run(KyuApplication.class, args);
	}

}
