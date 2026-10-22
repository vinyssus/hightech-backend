package cm.hitechdz.auth.service.media;

import cm.hitechdz.auth.exception.ApiException;
import com.cloudinary.Cloudinary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Appel réel du SDK Cloudinary (identifiants factices) : vérifie que la bibliothèque est compatible
 * avec le classpath Spring Boot et qu'un refus de Cloudinary devient une erreur 502 propre.
 */
class CloudinaryClientSmokeTest {

    @Test
    void real_sdk_call_with_bad_credentials_gives_clean_502() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("cloudinary", new Cloudinary(Map.of(
                "cloud_name", "hitech-dz-ci-inexistant", "api_key", "123", "api_secret", "abc", "secure", true)));
        CloudinaryImageStorageService service =
                new CloudinaryImageStorageService(beans.getBeanProvider(Cloudinary.class));

        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        Throwable thrown = catchThrowable(() ->
                service.upload(new MockMultipartFile("logo", "logo.png", "image/png", png), "hitech-dz/brands"));

        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus().value()).isEqualTo(502);
    }

    @Test
    void without_configuration_gives_503() {
        CloudinaryImageStorageService service =
                new CloudinaryImageStorageService(new StaticListableBeanFactory().getBeanProvider(Cloudinary.class));
        Throwable thrown = catchThrowable(() ->
                service.upload(new MockMultipartFile("logo", "logo.png", "image/png", new byte[]{1}), "x"));
        assertThat(((ApiException) thrown).getStatus().value()).isEqualTo(503);
    }
}
