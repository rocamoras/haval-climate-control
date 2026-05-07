// IClusterCallback.aidl
package com.autolink.clusterservice;

import com.autolink.cluster.ClusterMsgData;

interface IClusterCallback {
    void callbackMsg(int msgId, in ClusterMsgData data);
}