package com.hitit.aviation.api;

import java.math.BigDecimal;
import java.io.IOException;
import java.math.*;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.*;
import com.fasterxml.jackson.databind.*;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.module.SimpleModule;

@Configuration
public class JacksonConfig {
	static final class TwoDecimalDoubleSerializer extends JsonSerializer<Double>{
		@Override
		public void serialize(Double value, JsonGenerator gen, SerializerProvider provider) throws IOException{
			if(value==null || value.isNaN() || value.isInfinite()) {
				gen.writeNull();
				return;
			}
			gen.writeNumber(BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP));
		}
	}
	
	@Bean
	Jackson2ObjectMapperBuilderCustomizer twoDecimalDoubleCustomizer() {
		TwoDecimalDoubleSerializer serializer = new TwoDecimalDoubleSerializer();
		SimpleModule module = new SimpleModule("twoDecimalDoubles");
		module.addSerializer(Double.class, serializer);
		module.addSerializer(Double.TYPE, serializer);
		return builder -> builder.modulesToInstall(module);
	}
}
