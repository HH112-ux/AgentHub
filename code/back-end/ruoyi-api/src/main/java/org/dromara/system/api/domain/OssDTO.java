package org.dromara.system.api.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * OSS 文件简要信息对象。
 *
 * @author Lion Li
 */
@Data
@NoArgsConstructor
public class OssDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 对象存储主键
     */
    private Long ossId;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 原名
     */
    private String originalName;

    /**
     * 文件后缀名
     */
    private String fileSuffix;

    /**
     * URL地址
     */
    private String url;

    /**
     * 上传用户主键（用于校验文件归属，避免借用他人 ossId）
     */
    private Long createBy;

}
