package com.len.ticketing.application.reservation;

import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;

public class SeatAlreadyReservedException extends BusinessException {

    public SeatAlreadyReservedException() {
        super(ErrorCode.ALREADY_RESERVED);
    }

    public SeatAlreadyReservedException(String detailMessage) {
        super(ErrorCode.ALREADY_RESERVED, detailMessage);
    }
}
