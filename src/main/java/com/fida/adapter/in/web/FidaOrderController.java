package com.fida.adapter.in.web;

import com.fida.domain.port.in.ProcessTradingRecordUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fida")
@RequiredArgsConstructor
public class FidaOrderController {

    private final ProcessTradingRecordUseCase useCase;

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void trigger() {
        useCase.process();
    }
}
