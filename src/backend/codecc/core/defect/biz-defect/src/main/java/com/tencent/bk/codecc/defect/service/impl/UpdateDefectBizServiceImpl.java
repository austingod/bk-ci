/*
 * Tencent is pleased to support the open source community by making BlueKing available.
 * Copyright (C) 2017-2018 THL A29 Limited, a Tencent company. All rights reserved.
 * Licensed under the MIT License (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * http://opensource.org/licenses/MIT
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.bk.codecc.defect.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.Sets;
import com.tencent.bk.codecc.defect.dao.mongorepository.DefectRepository;
import com.tencent.bk.codecc.defect.dao.mongorepository.TaskLogRepository;
import com.tencent.bk.codecc.defect.dao.mongotemplate.DefectDao;
import com.tencent.bk.codecc.defect.dao.redis.StatisticDao;
import com.tencent.bk.codecc.defect.model.DefectEntity;
import com.tencent.bk.codecc.defect.model.TaskLogEntity;
import com.tencent.bk.codecc.defect.service.IUpdateDefectBizService;
import com.tencent.bk.codecc.defect.utils.ThirdPartySystemCaller;
import com.tencent.bk.codecc.defect.vo.DefectBaseVO;
import com.tencent.bk.codecc.defect.vo.DefectDetailVO;
import com.tencent.bk.codecc.defect.vo.UpdateDefectVO;
import com.tencent.bk.codecc.task.vo.TaskDetailVO;
import com.tencent.devops.common.constant.ComConstants;
import com.tencent.devops.common.util.JsonUtil;
import com.tencent.devops.common.util.PathUtils;
import org.apache.commons.lang3.tuple.Pair;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.tencent.devops.common.constant.ComConstants.StaticticItem;

/**
 * 更新告警服务实现类
 *
 * @version V1.0
 * @date 2019/11/21
 */
@Service
@Slf4j
public class UpdateDefectBizServiceImpl implements IUpdateDefectBizService {
    @Autowired
    protected DefectRepository defectRepository;

    @Autowired
    protected DefectDao defectDao;

    @Autowired
    protected TaskLogRepository taskLogRepository;

    @Autowired
    protected StatisticDao statisticDao;

    @Autowired
    private ThirdPartySystemCaller thirdPartySystemCaller;

    /**
     * 更新告警状态
     * 1.在codecc上new（即不被忽略、不被路径或规则屏蔽），在platform上已修复的告警，要标志为已修复
     * 2.在codecc上已修复，在platform上未修复的告警，要标志为未修复
     *
     * @param updateDefectVO
     * @return
     */
    @Override
    public void updateDefectStatus(UpdateDefectVO updateDefectVO) {
        log.info("begin to update defect status,taskId:{},toolName:{},buildId:{},size:{}", updateDefectVO.getTaskId(),
                updateDefectVO.getToolName(), updateDefectVO.getBuildId(), updateDefectVO.getDefectList().size());

        Long taskId = updateDefectVO.getTaskId();
        String toolName = updateDefectVO.getToolName();
        List<DefectDetailVO> defectList = updateDefectVO.getDefectList();
        String buildId = updateDefectVO.getBuildId();
        Map<String, Integer> checkerCountMap = new HashMap<>();
        if (CollectionUtils.isNotEmpty(defectList)) {
            TaskLogEntity taskLogEntity = taskLogRepository.findByTaskIdAndToolNameAndBuildId(taskId, toolName, buildId);
            String buildNum = taskLogEntity.getBuildNum();

            Map<String, DefectDetailVO> defectDetailVOMap = defectList.stream().collect(Collectors.toMap(DefectBaseVO::getId, Function.identity(), (k, v) -> v));
            List<DefectEntity> defectEntityList = defectRepository.findStatusAndAuthorAndSeverityByTaskIdAndToolNameAndIdIn(taskId, toolName, defectDetailVOMap.keySet());

            TaskDetailVO taskDetailVO = thirdPartySystemCaller.getTaskInfoWithoutToolsByTaskId(taskId);
            Set<String> filterPathSet = getFilterPaths(taskDetailVO);

            // 统计新增、关闭、遗留
            int closeCount = 0;
            int existCount = 0;
            int fixedCount = 0;
            int excludeCount = 0;
            int existPromptCount = 0;
            int existNormalCount = 0;
            int existSeriousCount = 0;
            Set<String> existAuthors = Sets.newHashSet();
            List<DefectEntity> needUpdateDefectList = new ArrayList<>();
            long currTime = System.currentTimeMillis();
            for (DefectEntity defectEntity : defectEntityList) {
                String id = defectEntity.getId();
                int status = defectEntity.getStatus();

                int codeccStatus = status;
                DefectDetailVO platformDefect = defectDetailVOMap.get(id);
                Integer platformStatus = platformDefect.getStatus();

                // 1.在codecc上new（即不被忽略、不被路径或规则屏蔽），在platform上已修复的告警，要标志为已修复
                if (codeccStatus == ComConstants.DefectStatus.NEW.value() && platformStatus == ComConstants.DefectStatus.FIXED.value()) {
                    status = codeccStatus | ComConstants.DefectStatus.FIXED.value();
                    defectEntity.setFixedTime(currTime);
                    defectEntity.setFixedBuildNumber(buildNum);
                    closeCount++;
                    fixedCount++;
                } else {
                    if ((codeccStatus & ComConstants.DefectStatus.FIXED.value()) > 0 && (platformStatus & ComConstants.DefectStatus.FIXED.value()) == 0) {
                        status = codeccStatus - ComConstants.DefectStatus.FIXED.value();
                        defectEntity.setFixedBuildNumber(null);
                    }
                }

                // 如果经过上面判断后，告警是未修复的，需要判断告警是否被路径屏蔽
                if ((status & ComConstants.DefectStatus.FIXED.value()) == 0) {
                    // 当前未被屏蔽但是命中屏蔽路径的，需要把告警屏蔽掉
                    if ((status & ComConstants.DefectStatus.PATH_MASK.value()) == 0
                            && PathUtils.checkIfMaskByPath(defectEntity.getFilePathname(), filterPathSet)) {
                        status = status | ComConstants.DefectStatus.PATH_MASK.value();
                        defectEntity.setExcludeTime(currTime);
                        closeCount++;
                        excludeCount++;
                    }
                    // 当前被屏蔽但是未命中屏蔽路径的，需要把告警放出来
                    else {
                        if ((status & ComConstants.DefectStatus.PATH_MASK.value()) > 0
                                && !PathUtils.checkIfMaskByPath(defectEntity.getFilePathname(), filterPathSet)) {
                            status = status - ComConstants.DefectStatus.PATH_MASK.value();
                            defectEntity.setExcludeTime(0);
                        }
                    }
                }


                if (status != codeccStatus ||
                        (StringUtils.isNotEmpty(platformDefect.getFilePathname()) && !platformDefect.getFilePathname().equals(defectEntity.getFilePathname()))) {
                    defectEntity.setStatus(status);
                    defectEntity.setFilePathname(platformDefect.getFilePathname());
                    needUpdateDefectList.add(defectEntity);
                }

                if (status == ComConstants.DefectStatus.NEW.value()) {
                    existCount++;
                    existAuthors.addAll(CollectionUtils.isEmpty(defectEntity.getAuthorList()) ? new ArrayList<>() : defectEntity.getAuthorList());
                    if ((defectEntity.getSeverity() & ComConstants.PROMPT) > 0) {
                        existPromptCount++;
                    }
                    if ((defectEntity.getSeverity() & ComConstants.NORMAL) > 0) {
                        existNormalCount++;
                    }
                    if ((defectEntity.getSeverity() & ComConstants.SERIOUS) > 0) {
                        existSeriousCount++;
                    }
                }
                // 获取规则数据
                Integer count = checkerCountMap.get(defectEntity.getCheckerName());
                if (count == null) {
                    count = 0;
                }
                checkerCountMap.put(defectEntity.getCheckerName(), ++count);
            }

            List<Pair<StaticticItem, Integer>> defectCountList = Arrays.asList(
                Pair.of(StaticticItem.EXIST, existCount),
                Pair.of(StaticticItem.CLOSE, closeCount),
                Pair.of(StaticticItem.FIXED, fixedCount),
                Pair.of(StaticticItem.EXCLUDE, excludeCount),
                Pair.of(StaticticItem.EXIST_PROMPT, existPromptCount),
                Pair.of(StaticticItem.EXIST_NORMAL, existNormalCount),
                Pair.of(StaticticItem.EXIST_SERIOUS, existSeriousCount));
            statisticDao.increaseDefectCountByStatusBatch(taskId, toolName, buildNum, defectCountList);
            statisticDao.addNewAndExistAuthors(taskId, toolName, buildNum, Sets.newHashSet(), existAuthors);

            // 写入规则统计数据
            statisticDao.increaseDefectCheckerCountBatch(taskId, toolName, buildNum, checkerCountMap);

            defectDao.batchUpdateDefectStatusFixedBit(taskId, needUpdateDefectList);
        }

        log.info("update defectStatus success.");
    }

    @Override
    public void updateDefects(UpdateDefectVO updateDefectVO) {
        log.info("begin to update defects, taskId:{}, toolName:{}, buildId:{}, size:{}", updateDefectVO.getTaskId(),
                updateDefectVO.getToolName(), updateDefectVO.getBuildId(), updateDefectVO.getDefectList().size());

        String defectListJson = JsonUtil.INSTANCE.toJson(updateDefectVO.getDefectList());
        List<DefectEntity> defectList =
            JsonUtil.INSTANCE.to(defectListJson, new TypeReference<List<DefectEntity>>() {});

        defectDao.batchUpdateDefectDetail(updateDefectVO.getTaskId(), updateDefectVO.getToolName(), defectList);

        log.info("success update defects, taskId:{}, toolName:{}, buildId:{}, size:{}", updateDefectVO.getTaskId(),
                updateDefectVO.getToolName(), updateDefectVO.getBuildId(), updateDefectVO.getDefectList().size());
    }

    private Set<String> getFilterPaths(TaskDetailVO taskDetailVO) {
        return new HashSet<>(taskDetailVO.getAllFilterPaths());
    }
}
