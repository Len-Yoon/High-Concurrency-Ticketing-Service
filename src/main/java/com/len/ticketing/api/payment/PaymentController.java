package com.len.ticketing.api.payment;

import com.len.ticketing.api.payment.dto.PaymentMockSuccessRequest;
import com.len.ticketing.api.payment.dto.PaymentReadyRequest;
import com.len.ticketing.api.payment.dto.PaymentReadyResponse;
import com.len.ticketing.api.payment.dto.PaymentResultResponse;
import com.len.ticketing.application.payment.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/payment")
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/ready")
    public PaymentReadyResponse ready(@RequestBody PaymentReadyRequest request) {
        var result = paymentService.ready(
                request.userId(),
                request.scheduleId(),
                request.seatNo()
        );
        return new PaymentReadyResponse(
                result.orderNo(),
                result.amount(),
                result.message()
        );
    }

    @PostMapping("/mock-success")
    public PaymentResultResponse mockSuccess(@RequestBody PaymentMockSuccessRequest request) {
        var result = paymentService.mockSuccess(request.orderNo());
        return new PaymentResultResponse(result.success(), result.message());
    }
}
