package com.atlas.shared.web;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * HTTP representation only; exact decimal financial amounts never enter JavaScript as binary
 * floats.
 */
@ControllerAdvice(
    basePackages = {
      "com.atlas.modules.finance.adapter.in.web",
      "com.atlas.modules.payroll.adapter.in.web",
      "com.atlas.modules.talent.adapter.in.web"
    })
public final class FinancialDecimalAdvice implements ResponseBodyAdvice<Object> {
  public boolean supports(
      MethodParameter type, Class<? extends HttpMessageConverter<?>> converter) {
    return true;
  }

  public Object beforeBodyWrite(
      Object body,
      MethodParameter type,
      MediaType contentType,
      Class<? extends HttpMessageConverter<?>> converter,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    return transport(body);
  }

  public static Object transport(Object value) {
    if (value == null) return null;
    if (value instanceof BigDecimal decimal) return decimal.toPlainString();
    if (value instanceof Collection<?> list)
      return list.stream().map(FinancialDecimalAdvice::transport).toList();
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      map.forEach((k, v) -> result.put(String.valueOf(k), transport(v)));
      return result;
    }
    if (value.getClass().isRecord()) {
      var result = new LinkedHashMap<String, Object>();
      for (var field : value.getClass().getRecordComponents())
        try {
          result.put(field.getName(), transport(field.getAccessor().invoke(value)));
        } catch (ReflectiveOperationException e) {
          throw new IllegalStateException("Financial response mapping failed", e);
        }
      return result;
    }
    return value;
  }
}
