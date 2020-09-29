package com.zbkj.crmeb.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.common.PageParamRequest;
import com.constants.Constants;
import com.exception.CrmebException;
import com.github.pagehelper.PageHelper;
import com.utils.RedisUtil;
import com.zbkj.crmeb.system.dao.SystemConfigDao;
import com.zbkj.crmeb.system.model.SystemConfig;
import com.zbkj.crmeb.system.request.SystemFormCheckRequest;
import com.zbkj.crmeb.system.request.SystemFormItemCheckRequest;
import com.zbkj.crmeb.system.service.SystemAttachmentService;
import com.zbkj.crmeb.system.service.SystemConfigService;
import com.zbkj.crmeb.system.service.SystemFormTempService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
* @author Mr.Zhang
* @Description SystemConfigServiceImpl 接口实现
* @since 2020-04-13
*/
@Service
public class SystemConfigServiceImpl extends ServiceImpl<SystemConfigDao, SystemConfig> implements SystemConfigService {

    @Resource
    private SystemConfigDao dao;

    @Autowired
    private SystemFormTempService systemFormTempService;

    @Autowired
    private SystemAttachmentService systemAttachmentService;

    @Autowired
    private RedisUtil redisUtil;

    private static final String redisKey = Constants.CONFIG_LIST;

    @Value("${server.asyncConfig}")
    private Boolean asyncConfig;

    /**
     * 搜索列表
     * @param pageParamRequest PageParamRequest 分页参数
     * @author Mr.Zhang
     * @since 2020-04-16
     * @return List<SystemConfig>
     */
    @Override
    public List<SystemConfig> getList(PageParamRequest pageParamRequest) {
        PageHelper.startPage(pageParamRequest.getPage(), pageParamRequest.getLimit());
        LambdaQueryWrapper<SystemConfig> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.select(
                SystemConfig::getId,
                SystemConfig::getTitle,
                SystemConfig::getName,
                SystemConfig::getValue,
                SystemConfig::getStatus

        );
        lambdaQueryWrapper.eq(SystemConfig::getStatus, false);
        return dao.selectList(lambdaQueryWrapper);
    }

    /**
     * 根据menu name 获取 value
     * @param name menu name
     * @author Mr.Zhang
     * @since 2020-04-16
     * @return String
     */
    @Override
    public String getValueByKey(String name) {
        return get(name);
    }


    /**
     * 同时获取多个配置
     * @param keys 多个配置key
     * @return List<String>
     */
    @Override
    public List<String> getValuesByKes(List<String> keys) {
        List<String> result = new ArrayList<>();
        for (String key : keys) {
            result.add(getValueByKey(key));
        }
        return result;
    }

    /**
     * 根据 name 获取 value 找不到抛异常
     * @param name menu name
     * @author Mr.Zhang
     * @since 2020-04-16
     * @return String
     */
    @Override
    public String getValueByKeyException(String name) {
        String value = get(name);
        if(null == value){
            throw new CrmebException("没有找到"+ name +"数据");
        }

        return value;
    }

    /**
     * 整体保存表单数据
     * @param systemFormCheckRequest SystemFormCheckRequest 数据保存
     * @author Mr.Zhang
     * @since 2020-04-13
     * @return boolean
     */
    @Override
    public boolean saveForm(SystemFormCheckRequest systemFormCheckRequest) {
        //检测form表单，并且返回需要添加的数据
        systemFormTempService.checkForm(systemFormCheckRequest);

        List<SystemConfig> systemConfigSaveList = new ArrayList<>();
        List<SystemConfig> systemConfigUpdateList = new ArrayList<>();
        List<SystemConfig> systemConfigDeleteList = new ArrayList<>();

        //批量添加
        for (SystemFormItemCheckRequest systemFormItemCheckRequest : systemFormCheckRequest.getFields()) {
            SystemConfig systemConfig = new SystemConfig();
            systemConfig.setName(systemFormItemCheckRequest.getName());

            String value = systemAttachmentService.clearPrefix(systemFormItemCheckRequest.getValue());
            if(StringUtils.isBlank(value)){
                //去掉图片域名之后没有数据则说明当前数据就是图片域名
                value = systemFormItemCheckRequest.getValue();
            }

            SystemConfig data = selectByName(systemFormItemCheckRequest.getName());

            systemConfig.setValue(value);
            systemConfig.setFormId(systemFormCheckRequest.getId());
            systemConfig.setTitle(systemFormItemCheckRequest.getTitle());

            if(null == data){
                systemConfigSaveList.add(systemConfig);
            }else{
                systemConfig.setId(data.getId());
                systemConfigUpdateList.add(systemConfig);
            }
        }

        //拿到之前form下的所有数据
        List<SystemConfig> formDataList = selectByFormId(systemFormCheckRequest.getId());

        //添加或者更新数据
        saveBatch(systemConfigSaveList);
        saveOrUpdateBatch(systemConfigUpdateList);


        //所有需要修改的数据
        systemConfigSaveList.addAll(systemConfigUpdateList);

        List<String> collectNameList = systemConfigSaveList.stream().map(SystemConfig::getName).collect(Collectors.toList());

        //删除老的数据且不在新form提交的数据
        if(null != formDataList && formDataList.size() > 0){
            for (SystemConfig systemConfig : formDataList) {
                if(!collectNameList.contains(systemConfig.getName())){
                    systemConfig.setStatus(true);
                    systemConfigDeleteList.add(systemConfig);
                }
            }
        }
        if(systemConfigDeleteList.size() > 0){
            dao.deleteBatchIds(systemConfigDeleteList.stream().map(SystemConfig::getId).collect(Collectors.toList()));

        }

        systemConfigDeleteList.addAll(systemConfigSaveList);
        async(systemConfigDeleteList);

        return true;
    }

    private List<SystemConfig> selectByFormId(Integer formId) {
        LambdaQueryWrapper<SystemConfig> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(SystemConfig::getFormId, formId);
        return dao.selectList(lambdaQueryWrapper);
    }

    private SystemConfig selectByName(String value) {
        LambdaQueryWrapper<SystemConfig> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(SystemConfig::getName, value).eq(SystemConfig::getStatus, false);
        return dao.selectOne(lambdaQueryWrapper);
    }


    /**
     * updateStatusByGroupId
     * @param formId Integer formId
     * @author Mr.Zhang
     * @since 2020-04-16
     */
    private void updateStatusByFormId(Integer formId){
        LambdaQueryWrapper<SystemConfig> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(SystemConfig::getFormId, formId).eq(SystemConfig::getStatus, false);

        SystemConfig systemConfig = new SystemConfig();
        systemConfig.setStatus(true);
        update(systemConfig, lambdaQueryWrapper);

    }

    /**
     * deleteStatusByGroupId
     * @param formId Integer formId
     * @author Mr.Zhang
     * @since 2020-04-16
     */
    private void deleteStatusByFormId(Integer formId){
        LambdaQueryWrapper<SystemConfig> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        //删除已经隐藏的数据
        lambdaQueryWrapper.eq(SystemConfig::getFormId, formId).eq(SystemConfig::getStatus, true);
        List<SystemConfig> systemConfigList = dao.selectList(lambdaQueryWrapper);
        dao.delete(lambdaQueryWrapper);
        async(systemConfigList);
    }


    /**
     * 保存或更新配置数据
     * @param name 菜单名称
     * @param value 菜单值
     * @return boolean
     */
    @Override
    public boolean updateOrSaveValueByName(String name, String value) {
        value = systemAttachmentService.clearPrefix(value);

        LambdaQueryWrapper<SystemConfig> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(SystemConfig::getName, name);
        List<SystemConfig> systemConfigs = dao.selectList(lambdaQueryWrapper);
        if(systemConfigs.size() >= 2){
            throw new CrmebException("配置名称存在多个请检查配置 eb_system_config 重复数据："+name+"条数："+systemConfigs.size());
        }else if(systemConfigs.size() == 1){
            systemConfigs.get(0).setValue(value);
            updateById(systemConfigs.get(0));
            asyncRedis(name);
            return true;
        }else {
            save(new SystemConfig().setName(name).setValue(value));
            asyncRedis(name);
            return true;
        }
    }


    /**
     * 根据formId查询数据
     * @param formId Integer id
     * @author Mr.Zhang
     * @since 2020-04-16
     * @return HashMap<String, String>
     */
    @Override
    public HashMap<String, String> info(Integer formId){
        LambdaQueryWrapper<SystemConfig> lambdaQueryWrapper1 = new LambdaQueryWrapper<>();
//        lambdaQueryWrapper1.eq(SystemConfig::getStatus, false).eq(SystemConfig::getFormId, formId);
        lambdaQueryWrapper1.eq(SystemConfig::getFormId, formId);
        List<SystemConfig> systemConfigList = dao.selectList(lambdaQueryWrapper1);
        if(systemConfigList.size() < 1){
            return null;
        }
        HashMap<String, String> map = new HashMap<>();
        for (SystemConfig systemConfig : systemConfigList) {
            map.put(systemConfig.getName(), systemConfig.getValue());
        }
        map.put("id", formId.toString());
        return map;
    }

    /**
     * 根据name查询数据
     * @param name name
     * @author Mr.Zhang
     * @since 2020-04-16
     * @return boolean
     */
    @Override
    public boolean checkName(String name){
        String value = get(name);
        return value == null;
    }



    /**
     * 把数据同步到redis
     * @param name name
     * @author Mr.Zhang
     * @since 2020-04-16
     */
    private void asyncRedis(String name){
        LambdaQueryWrapper<SystemConfig> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(SystemConfig::getName, name);
        List<SystemConfig> systemConfigList = dao.selectList(lambdaQueryWrapper);
        if(systemConfigList.size() == 0){
            //说明数据已经被删除了
            deleteRedis(name);
            return;
        }

        async(systemConfigList);
    }

    /**
     * 把数据同步到redis
     * @param systemConfigList List<SystemConfig> 需要同步的数据
     * @author Mr.Zhang
     * @since 2020-04-16
     */
    private void async(List<SystemConfig> systemConfigList){
        if (!asyncConfig && systemConfigList.size() < 1) {
            //如果配置没有开启
            return;
        }

        for (SystemConfig systemConfig : systemConfigList) {
            if(null != systemConfig.getStatus() && systemConfig.getStatus()){
                //隐藏之后，删除redis的数据
                deleteRedis(systemConfig.getName());
                continue;
            }
            redisUtil.hmSet(redisKey, systemConfig.getName(), systemConfig.getValue());
        }
    }

    /**
     * 把数据同步到redis
     * @param name String
     * @author Mr.Zhang
     * @since 2020-04-16
     */
    private void deleteRedis(String name){
        if (!asyncConfig) {
            //如果配置没有开启
            return;
        }
        redisUtil.hmDelete(redisKey, name);
    }

    /**
     * 把数据同步到redis
     * @param name String
     * @author Mr.Zhang
     * @since 2020-04-16
     * @return String
     */
    private String get(String name){
        if (!asyncConfig) {
            //如果配置没有开启
            LambdaQueryWrapper<SystemConfig> lambdaQueryWrapper = new LambdaQueryWrapper<>();
            lambdaQueryWrapper.eq(SystemConfig::getStatus, false).eq(SystemConfig::getName, name);
            SystemConfig systemConfig = dao.selectOne(lambdaQueryWrapper);
            if(null == systemConfig || StringUtils.isBlank(systemConfig.getValue())){
                return null;
            }

            return systemConfig.getValue();

        }
        setRedisByVoList();
        Object data = redisUtil.hmGet(redisKey, name);
        if(null == data || StringUtils.isBlank(data.toString())){
            //没有找到数据
            return null;
        }

        //去数据库查找，然后写入redis
        return data.toString();
    }

    /**
     * 把数据同步到redis, 此方法适用于redis为空的时候进行一次批量输入
     * @author Mr.Zhang
     * @since 2020-04-16
     */
    private void setRedisByVoList(){
        //检测redis是否为空
        Long size = redisUtil.getHashSize(redisKey);
        if(size > 0 || !asyncConfig){
            return;
        }

        LambdaQueryWrapper<SystemConfig> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(SystemConfig::getStatus, false);
        List<SystemConfig> systemConfigList = dao.selectList(lambdaQueryWrapper);
        async(systemConfigList);
    }
}
