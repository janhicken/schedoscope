/**
 * Copyright 2017 Otto (GmbH & Co KG)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.schedoscope.metascope.task;

import org.schedoscope.metascope.config.MetascopeConfig;
import org.schedoscope.metascope.exception.SchedoscopeConnectException;
import org.schedoscope.metascope.repository.jdbc.RawJDBCSqlRepository;
import org.schedoscope.metascope.service.MetascopeStatusService;
import org.schedoscope.metascope.task.model.View;
import org.schedoscope.metascope.task.model.ViewStatus;
import org.schedoscope.metascope.util.SchedoscopeUtil;
import org.schedoscope.metascope.util.StatusUtil;
import org.schedoscope.metascope.util.model.SchedoscopeInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class StatusTask extends Task {

    private static final Logger LOG = LoggerFactory.getLogger(StatusTask.class);

    @Autowired
    private MetascopeConfig metascopeConfig;

    @Autowired
    private MetascopeStatusService metascopeStatusService;

    @Override
    public boolean run(RawJDBCSqlRepository sqlRepository, long start) {
        for (SchedoscopeInstance schedoscopeInstance : metascopeConfig.getSchedoscopeInstances()) {
            String host = schedoscopeInstance.getHost();
            int port = schedoscopeInstance.getPort();
            LOG.info("Getting status information for (" + host + ":" + port + ")");

            ViewStatus viewStatus;
            try {
                viewStatus = SchedoscopeUtil.getViewStatus(false, false, null, host, port);
            } catch (SchedoscopeConnectException e) {
                LOG.warn("Could not retrieve Schedoscope status information (" + host + ":" + port + ")", e);
                continue;
            }

            Map<String, List<String>> tableAggregation = new HashMap<>();
            for (View view : viewStatus.getViews()) {
                List<String> statusesForTable = tableAggregation.get(view.viewPath());
                if (statusesForTable == null) {
                    statusesForTable = new ArrayList<>();
                }
                statusesForTable.add(view.getStatus());
                tableAggregation.put(view.viewPath(), statusesForTable);
                metascopeStatusService.setStatus(view.getName(), view.getStatus());
            }
            for (Map.Entry<String, List<String>> e : tableAggregation.entrySet()) {
                String status = StatusUtil.getStatus(e.getValue());
                metascopeStatusService.setStatus(e.getKey(), status);
            }

            LOG.info("Finished status update");

        }

        return true;
    }

}
