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
                service.upload(new MockMultipartFile("logo", "logo.png", "image/png", png), "hitech-dz/brands", null));

        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus().value()).isEqualTo(502);
    }

    @Test
    void without_configuration_gives_503() {
        CloudinaryImageStorageService service =
                new CloudinaryImageStorageService(new StaticListableBeanFactory().getBeanProvider(Cloudinary.class));
        Throwable thrown = catchThrowable(() ->
                service.upload(new MockMultipartFile("logo", "logo.png", "image/png", new byte[]{1}), "x", null));
        assertThat(((ApiException) thrown).getStatus().value()).isEqualTo(503);
    }

    @Test
    void incoming_transformation_with_and_without_logo() {
        assertThat(CloudinaryImageStorageService.incomingTransformation(null).generate())
                .isEqualTo("c_limit,h_1200,q_auto,w_1200");
        String withLogo = CloudinaryImageStorageService.incomingTransformation(
                new Watermark("hitech-dz/brand/watermark-default", "south_east", 0.33, 0.03, 100)).generate();
        assertThat(withLogo).isEqualTo("c_limit,h_1200,q_auto,w_1200/fl_relative,l_hitech-dz:brand:watermark-default,w_0.33"
                + "/fl_layer_apply,g_south_east,x_0.03,y_0.03");
        String faded = CloudinaryImageStorageService.incomingTransformation(
                new Watermark("hitech-dz/brand/logo", "center", 0.5, 0.03, 60)).generate();
        assertThat(faded).isEqualTo("c_limit,h_1200,q_auto,w_1200/fl_relative,l_hitech-dz:brand:logo,o_60,w_0.5/fl_layer_apply,g_center");
    }
}
