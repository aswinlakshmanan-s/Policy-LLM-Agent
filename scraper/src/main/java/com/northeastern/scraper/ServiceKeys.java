package com.northeastern.scraper;

import akka.actor.typed.receptionist.ServiceKey;

public final class ServiceKeys {
    private ServiceKeys() {}

    public static final ServiceKey<LoggerActor.LogMsg> LOGGER_KEY =
            ServiceKey.create(LoggerActor.LogMsg.class, "loggerService");
    public static final ServiceKey<PolicySearchActor.SearchMsg> SEARCH_KEY =
            ServiceKey.create(PolicySearchActor.SearchMsg.class, "searchService");
    public static final ServiceKey<LLMActor.LLMMsg> LLM_KEY =
            ServiceKey.create(LLMActor.LLMMsg.class, "llmService");
}
