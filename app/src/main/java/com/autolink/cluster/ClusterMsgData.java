package com.autolink.cluster;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Arrays;

/* loaded from: classes.dex */
public class ClusterMsgData implements Parcelable {
    public static final Parcelable.Creator<ClusterMsgData> CREATOR = new Parcelable.Creator<ClusterMsgData>() { // from class: com.autolink.cluster.ClusterMsgData.1
        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public ClusterMsgData createFromParcel(Parcel parcel) {
            ClusterMsgData clusterMsgData = new ClusterMsgData();
            clusterMsgData.readFromParcel(parcel);
            return clusterMsgData;
        }

        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public ClusterMsgData[] newArray(int i) {
            return new ClusterMsgData[i];
        }
    };
    private int flag = 0;
    private int FLAG_HAVE_INT = 1;
    private int FLAG_HAVE_INT_ARRAY = 2;
    private int int_value = 0;
    private int[] intArray_value = new int[0];

    @Override // android.os.Parcelable
    public int describeContents() {
        return 0;
    }

    @Override // android.os.Parcelable
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeInt(this.flag);
        if ((this.flag & this.FLAG_HAVE_INT) != 0) {
            parcel.writeInt(this.int_value);
        }
        if ((this.flag & this.FLAG_HAVE_INT_ARRAY) != 0) {
            parcel.writeInt(this.intArray_value.length);
            parcel.writeIntArray(this.intArray_value);
        }
    }

    public void readFromParcel(Parcel parcel) {
        int i = parcel.readInt();
        this.flag = i;
        if ((i & this.FLAG_HAVE_INT) != 0) {
            this.int_value = parcel.readInt();
        }
        if ((this.flag & this.FLAG_HAVE_INT_ARRAY) != 0) {
            int[] iArr = new int[parcel.readInt()];
            this.intArray_value = iArr;
            parcel.readIntArray(iArr);
        }
    }

    public void setIntValue(int i) {
        this.flag |= this.FLAG_HAVE_INT;
        this.int_value = i;
    }

    public void setIntArrayValue(int[] iArr) {
        this.flag |= this.FLAG_HAVE_INT_ARRAY;
        this.intArray_value = iArr;
    }

    public int getIntValue() {
        return this.int_value;
    }

    public int[] getIntArray_value() {
        return this.intArray_value;
    }

    public String toString() {
        return "ClusterMsgData{int_value=" + this.int_value + ", intArray_value=" + Arrays.toString(this.intArray_value) + '}';
    }
}