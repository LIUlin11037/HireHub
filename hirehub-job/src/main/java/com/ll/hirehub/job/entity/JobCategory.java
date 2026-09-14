package com.ll.hirehub.job.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("job_category")
public class JobCategory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long parentId;
    private String name;
    private Integer level;
    private Integer sort;
    private Integer status;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
