package com.logiplatform.integration;

import com.logiplatform.integration.control.OperationRetryPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OperationRetryPolicyTest {
    @Test void readsAreSafeToRetry(){assertEquals(OperationRetryPolicy.SAFE_TO_RETRY,OperationRetryPolicy.classify("GET_FLIGHT_STATUS",null));assertEquals(OperationRetryPolicy.SAFE_TO_RETRY,OperationRetryPolicy.classify("GET_BOOKING",null));}
    @Test void mutationsRequireIdempotency(){assertEquals(OperationRetryPolicy.NEVER_RETRY,OperationRetryPolicy.classify("BOOK",null));assertEquals(OperationRetryPolicy.CONDITIONALLY_RETRYABLE,OperationRetryPolicy.classify("BOOK","key-1"));assertEquals(OperationRetryPolicy.CONDITIONALLY_RETRYABLE,OperationRetryPolicy.classify("AWB_SUBMIT","key-2"));}
}
