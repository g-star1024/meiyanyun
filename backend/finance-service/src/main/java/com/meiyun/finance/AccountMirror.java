package com.meiyun.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 三账户只读镜像（对公活期/支付宝商户/微信商户），全站无资金动词按钮。 */
@Entity
@Table(name = "account_mirror")
@Getter @Setter @NoArgsConstructor
public class AccountMirror {

    @Id
    @Column(name = "acct_id", length = 16)
    private String acctId;

    @Column(name = "acct_name", length = 32)
    private String acctName;

    @Column(nullable = false)
    private Long balance;

    @Column(name = "acct_type", length = 16)
    private String acctType;
}
