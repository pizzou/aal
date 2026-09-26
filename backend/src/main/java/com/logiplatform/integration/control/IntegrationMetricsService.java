package com.logiplatform.integration.control;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

@Service
public class IntegrationMetricsService {
    private final MeterRegistry registry;
    public IntegrationMetricsService(MeterRegistry registry){this.registry=registry;}
    public Timer.Sample start(){return Timer.start(registry);}
    public void providerSuccess(String provider,long latencyMs){registry.counter("aal.integration.provider.calls","provider",provider,"result","success").increment();registry.timer("aal.integration.provider.latency","provider",provider).record(java.time.Duration.ofMillis(Math.max(0,latencyMs)));}
    public void providerFailure(String provider,long latencyMs,String code){registry.counter("aal.integration.provider.calls","provider",provider,"result","failure","error",code==null?"UNKNOWN":code).increment();registry.timer("aal.integration.provider.latency","provider",provider).record(java.time.Duration.ofMillis(Math.max(0,latencyMs)));}
    public void booking(String result){registry.counter("aal.booking.operations","result",result).increment();}
    public void webhook(String result){registry.counter("aal.webhook.events","result",result).increment();}
}
