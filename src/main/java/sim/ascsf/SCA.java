package sim.ascsf;

import java.util.UUID;

/**
 * Sustainable Capability Advertisement (SCA).
 *
 * Direct implementation of SCA Information Model v1.1. Only fields listed
 * as "Transmitted Fields" in that document appear here as instance fields.
 * Estimated_Network_Cost and Advertisement_Age are deliberately NOT fields
 * of this class -- per the Information Model, they are receiver-derived
 * and computed on demand by CollaborationUtility, never carried on the
 * message itself. Commitment_State is likewise not a field here -- it is
 * protocol state tracked externally by CoordinationProtocol, keyed by scaId.
 */
public class SCA {

    public enum Mode { REQUEST, OFFER }
    public enum TaskType { DS, DT }

    // ── 2.1 Identity ─────────────────────────────────────────────────────────
    public final String scaId;
    public final int providerId;      // pseudonymous handle = global DC index in this sim
    public final Mode mode;

    // ── 2.2 Task Description ────────────────────────────────────────────────
    public final TaskType taskType;          // REQUEST only (null for OFFER)
    public final double vCpuRequired;         // resource envelope
    public final double memoryGbRequired;
    public final double estDurationSec;

    // ── 2.3 Sustainability Advertisement (OFFER only, per v1.1 correction) ──
    // Left as Double (boxed) rather than double so that REQUEST-mode SCAs can
    // carry `null` here, structurally enforcing "carbon/renewable are
    // Offer-only" rather than relying on callers to remember to ignore them.
    public final Double forecastCarbonIntensity;      // gCO2/kWh, OFFER only
    public final Double forecastRenewableAvailability; // [0,1], OFFER only
    public final Double availableSchedulingCapacity;   // [0,1], OFFER only

    // ── 2.4 Temporal Information ────────────────────────────────────────────
    public final double issuedAtSec;
    public final double expiresAtSec;

    // ── Reference to the originating task, simulation-internal only ────────
    // (Not part of the wire schema in the Information Model -- this is a
    // simulation bookkeeping convenience so CoordinationProtocol can bind
    // the eventual Cloudlet without a separate lookup table. Equivalent to
    // an opaque task handle a real implementation would carry out-of-band.)
    public final long taskCloudletId;

    private SCA(Builder b) {
        this.scaId = b.scaId;
        this.providerId = b.providerId;
        this.mode = b.mode;
        this.taskType = b.taskType;
        this.vCpuRequired = b.vCpuRequired;
        this.memoryGbRequired = b.memoryGbRequired;
        this.estDurationSec = b.estDurationSec;
        this.forecastCarbonIntensity = b.forecastCarbonIntensity;
        this.forecastRenewableAvailability = b.forecastRenewableAvailability;
        this.availableSchedulingCapacity = b.availableSchedulingCapacity;
        this.issuedAtSec = b.issuedAtSec;
        this.expiresAtSec = b.expiresAtSec;
        this.taskCloudletId = b.taskCloudletId;
    }

    public boolean isExpired(double atTimeSec) {
        return atTimeSec > expiresAtSec;
    }

    public static Builder requestBuilder(int providerId, long taskCloudletId) {
        return new Builder(Mode.REQUEST, providerId, taskCloudletId);
    }

    public static Builder offerBuilder(int providerId, long taskCloudletId) {
        return new Builder(Mode.OFFER, providerId, taskCloudletId);
    }

    public static class Builder {
        private final String scaId = UUID.randomUUID().toString();
        private final int providerId;
        private final Mode mode;
        private final long taskCloudletId;
        private TaskType taskType;
        private double vCpuRequired;
        private double memoryGbRequired;
        private double estDurationSec;
        private Double forecastCarbonIntensity;
        private Double forecastRenewableAvailability;
        private Double availableSchedulingCapacity;
        private double issuedAtSec;
        private double expiresAtSec;

        private Builder(Mode mode, int providerId, long taskCloudletId) {
            this.mode = mode;
            this.providerId = providerId;
            this.taskCloudletId = taskCloudletId;
        }

        public Builder taskType(TaskType t) { this.taskType = t; return this; }
        public Builder resourceEnvelope(double vCpu, double memGb, double durSec) {
            this.vCpuRequired = vCpu; this.memoryGbRequired = memGb; this.estDurationSec = durSec;
            return this;
        }
        public Builder sustainability(double carbon, double renewable, double capacity) {
            if (mode != Mode.OFFER) {
                throw new IllegalStateException(
                    "Carbon/renewable/capacity fields are OFFER-only per SCA Information Model v1.1 Sec 2.3 -- "
                  + "attempted to set them on a REQUEST-mode SCA.");
            }
            this.forecastCarbonIntensity = carbon;
            this.forecastRenewableAvailability = renewable;
            this.availableSchedulingCapacity = capacity;
            return this;
        }
        public Builder validity(double issuedAtSec, double expiresAtSec) {
            this.issuedAtSec = issuedAtSec; this.expiresAtSec = expiresAtSec;
            return this;
        }
        public SCA build() { return new SCA(this); }
    }

    @Override
    public String toString() {
        return String.format(
            "SCA[%s mode=%s providerId=%d task=%d carbon=%s renewable=%s cap=%s expiresAt=%.1f]",
            scaId.substring(0, 8), mode, providerId, taskCloudletId,
            forecastCarbonIntensity, forecastRenewableAvailability, availableSchedulingCapacity,
            expiresAtSec);
    }
}