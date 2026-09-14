package com.ll.hirehub.company.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyVerifyResult {

    private boolean valid;
    private String businessStatus;
    private String message;
}
