package com.bablsoft.accessflow.scim.internal.config;

import com.bablsoft.accessflow.scim.internal.web.scim.ScimMediaTypes;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

/**
 * Teaches the Jackson HTTP converter the {@code application/scim+json} media type (#621), so IdPs
 * sending SCIM content types get normal (de)serialization. Purely additive — plain JSON handling
 * is unchanged.
 */
@Configuration(proxyBeanMethods = false)
class ScimWebConfiguration implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (var converter : converters) {
            if (converter instanceof JacksonJsonHttpMessageConverter jackson) {
                var mediaTypes = new ArrayList<MediaType>(jackson.getSupportedMediaTypes());
                if (!mediaTypes.contains(ScimMediaTypes.SCIM_JSON)) {
                    mediaTypes.add(ScimMediaTypes.SCIM_JSON);
                    jackson.setSupportedMediaTypes(mediaTypes);
                }
            }
        }
    }
}
