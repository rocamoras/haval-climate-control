// IClusterService.aidl
package com.autolink.clusterservice;

import com.autolink.cluster.ClusterMsgData;
import com.autolink.clusterservice.IClusterCallback;

interface IClusterService {
    void registerCallback(in IClusterCallback callback);
    void unregisterCallback(in IClusterCallback callback);
    void setMsg(int msgId, in ClusterMsgData data);
    void getMsg(int msgId);
    ClusterMsgData getMsgData(int msgId);
}