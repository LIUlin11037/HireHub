package com.ll.hirehub.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ll.hirehub.notification.entity.NotificationReceiver;
import com.ll.hirehub.notification.vo.NotificationVO;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface NotificationReceiverMapper extends BaseMapper<NotificationReceiver> {

    /** 查某用户的通知列表（join notification，map-underscore-to-camel-case 自动映射） */
    @Select("SELECT n.id, n.type, n.title, n.content, n.create_time, r.read_status, r.read_time " +
            "FROM notification n JOIN notification_receiver r ON n.id = r.notification_id " +
            "WHERE r.receiver_id = #{userId} AND n.deleted = 0 " +
            "ORDER BY n.create_time DESC")
    List<NotificationVO> selectByReceiver(Long userId);
}
