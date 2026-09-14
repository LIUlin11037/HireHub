package com.ll.hirehub.delivery.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("delivery_resume")
public class DeliveryResume {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deliveryId;
    private Long resumeId;
    private String resumeSnapshot;
    private LocalDateTime snapshotTime;
}
