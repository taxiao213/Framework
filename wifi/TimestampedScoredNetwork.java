package vl.vision.home.util.data.wifi;
import android.net.ScoredNetwork;
import android.os.Parcel;
import android.os.Parcelable;
/**
 * Created by hanqq on 2022/2/21
 * Email:yin13753884368@163.com
 * CSDN:http://blog.csdn.net/yin13753884368/article
 * Github:https://github.com/taxiao213
 */
public class TimestampedScoredNetwork implements Parcelable {
    private ScoredNetwork mScore;

    private long mUpdatedTimestampMillis;

    TimestampedScoredNetwork(ScoredNetwork score, long updatedTimestampMillis) {
        this.mScore = score;
        this.mUpdatedTimestampMillis = updatedTimestampMillis;
    }

    protected TimestampedScoredNetwork(Parcel in) {
        this.mScore = (ScoredNetwork)in.readParcelable(ScoredNetwork.class.getClassLoader());
        this.mUpdatedTimestampMillis = in.readLong();
    }

    public void update(ScoredNetwork score, long updatedTimestampMillis) {
        this.mScore = score;
        this.mUpdatedTimestampMillis = updatedTimestampMillis;
    }

    public ScoredNetwork getScore() {
        return this.mScore;
    }

    public long getUpdatedTimestampMillis() {
        return this.mUpdatedTimestampMillis;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable((Parcelable)this.mScore, flags);
        dest.writeLong(this.mUpdatedTimestampMillis);
    }

    public static final Parcelable.Creator<TimestampedScoredNetwork> CREATOR = new Parcelable.Creator<TimestampedScoredNetwork>() {
        public TimestampedScoredNetwork createFromParcel(Parcel in) {
            return new TimestampedScoredNetwork(in);
        }

        public TimestampedScoredNetwork[] newArray(int size) {
            return new TimestampedScoredNetwork[size];
        }
    };
}