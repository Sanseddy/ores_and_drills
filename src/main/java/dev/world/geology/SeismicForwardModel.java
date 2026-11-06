package dev.world.geology;

import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Forward model of one survey: synthesises what the station and its geophones record over every map
 * pixel and depth bin, then processes it the way an interpreter would — remove the regional
 * background, pick the dominant residual per pixel. Signals are sums of analytic reflector
 * responses (see {@link SeismicMath}); nothing is propagated block by block.
 *
 * <p>Errors are part of the measurement: noise grows with depth and shrinks with independent
 * receivers, reflectors are blurred by the lateral resolution and shifted by a positioning error of
 * about that size, and depths are scaled by the velocity error. All of them are deterministic for a
 * given configuration, so repeating an identical survey reproduces the same picture instead of
 * averaging its errors away.
 */
public final class SeismicForwardModel {
    /** Typical position error as a share of the lateral resolution. */
    public static final double POSITION_ERROR_SCALE = 0.6D;

    /**
     * Share of a cave zone's reflectivity that arrives as a coherent reflection. Specular
     * reflection needs a surface smooth over the Fresnel zone; a rugose cave network mostly
     * scatters, which a record shows as noise and lost energy rather than as a bright event.
     * Minecraft caves are irregular voids smaller than the regional wavelength; calibrated against
     * the real overworld cave field (OverworldCalibrationGameTests).
     */
    public static final double CAVITY_COHERENCE = 0.10D;

    /** Amplitude of the incoherent scattering from a cave zone, as a share of its reflectivity. */
    public static final double CAVITY_SCATTERING = 0.05D;

    /**
     * The background spread is read at this quantile of |residual/σ|: low enough that a target
     * covering most of a detailed map does not count as background, high enough to be stable.
     */
    private static final double BACKGROUND_QUANTILE = 0.3D;

    /** The same quantile of |x| for standard Gaussian noise. */
    private static final double NOISE_QUANTILE_RATIO = 0.3853D;

    /**
     * Lateral summation radius as a share of the Fresnel-limited resolution. Migration collapses a
     * reflector's energy from all traces of its aperture, which raises coherent signal over
     * independent trace noise; a Gaussian of this width reproduces that gain and gives noise the
     * blotchy, resolution-sized texture of real maps.
     */
    public static final double MIGRATION_APERTURE = 0.5D;

    /** Attenuation anomaly that saturates the 0–255 attribute. */
    public static final double ATTENUATION_FULL_SCALE = 1.5D;

    private static final long NOISE_SALT = 0x4E4F495345L;
    private static final long ATTENUATION_SALT = 0x4154544EL;
    private static final long HORIZON_SALT = 0x484F52495AL;
    private static final long VELOCITY_SALT = 0x56454C4F43495459L;

    /** {@link Output#sources()} value of a pixel no single reflector explains. */
    public static final int SOURCE_NONE = -1;

    /** {@link Output#sources()} value of a pixel dominated by the cave field. */
    public static final int SOURCE_CAVITY = -2;

    /**
     * A source is credited with a pixel's event when it makes at least this share of the event's
     * structural signal and stands above the noise; otherwise the event is noise or the sum of many
     * small contributions.
     */
    private static final double ATTRIBUTION_SHARE = 0.5D;

    private SeismicForwardModel() {}

    /**
     * The map and, per pixel, which model element produced its dominant event: an index into {@link
     * SurveyInput#bodies()}, {@link #SOURCE_CAVITY} or {@link #SOURCE_NONE}. Sources stay on the
     * server; interpretation turns them into identification probabilities.
     */
    /**
     * @param volume signed residual-to-noise ratio of every pixel and depth bin after migration,
     *     index {@code pixel * bins + bin}; what the LiDAR view draws its seismic points from
     */
    public record Output(SurveyMap map, int[] sources, float[] volume, int bins) {}

    /** Receiver geometry as seen from one column. */
    public record ArrayView(double geophoneReceivers, double allReceivers, double coverage) {
        /** Migration focusing of the Fresnel zone, see {@link ArrayGeometry#lateralSigma}. */
        public double focusing() {
            return 1.0D + geophoneReceivers * (0.3D + 0.7D * coverage);
        }
    }

    /** A body as this survey sees it: shifted by the positioning error and depth-scaled. */
    public record BodyView(
            SubsurfaceBody body,
            double x,
            double z,
            double topY,
            double bottomY,
            double centerY,
            double wavelength,
            double absorption) {
        public boolean thin() {
            return body.thickness() < wavelength * 0.25D;
        }

        public double thinFactor() {
            return SeismicMath.thinBedFactor(body.thickness(), wavelength);
        }
    }

    public static ArrayView arrayAt(SurveyInput input, double px, double pz) {
        int geophones = input.geophoneX().length;
        double[] xs = new double[geophones + 1];
        double[] zs = new double[geophones + 1];
        double[] weights = new double[geophones + 1];
        double[] geoWeights = new double[geophones];
        xs[0] = input.centerX();
        zs[0] = input.centerZ();
        weights[0] = 1.0D;
        for (int i = 0; i < geophones; i++) {
            xs[i + 1] = input.geophoneX()[i];
            zs[i + 1] = input.geophoneZ()[i];
            double weight =
                    ArrayGeometry.illumination(
                            input.centerX(),
                            input.centerZ(),
                            input.geophoneX()[i],
                            input.geophoneZ()[i],
                            px,
                            pz);
            weights[i + 1] = weight;
            geoWeights[i] = weight;
        }
        double all =
                ArrayGeometry.effectiveReceivers(xs, zs, weights, ArrayGeometry.CORRELATION_LENGTH);
        double geo =
                geophones == 0
                        ? 0.0D
                        : ArrayGeometry.effectiveReceivers(
                                input.geophoneX(),
                                input.geophoneZ(),
                                geoWeights,
                                ArrayGeometry.CORRELATION_LENGTH);
        double coverage = ArrayGeometry.azimuthalCoverage(px, pz, xs, zs, weights);
        return new ArrayView(geo, Math.max(1.0D, all), coverage);
    }

    /** Relative velocity error of the whole spread, from moveout over the geophone offsets. */
    public static double velocityError(SurveyInput input) {
        int geophones = input.geophoneX().length;
        double[] offsets = new double[geophones + 1];
        double[] weights = new double[geophones + 1];
        weights[0] = 1.0D;
        for (int i = 0; i < geophones; i++) {
            offsets[i + 1] =
                    Math.hypot(
                            input.geophoneX()[i] - input.centerX(),
                            input.geophoneZ()[i] - input.centerZ());
            weights[i + 1] = 1.0D;
        }
        double velocity =
                NaturalGeology.host(input.setting(), input.datumY() - SurveyMetrics.REFERENCE_DEPTH)
                        .velocity();
        double error =
                ArrayGeometry.velocityRelativeError(
                        offsets,
                        weights,
                        SurveyMetrics.REFERENCE_DEPTH,
                        velocity,
                        input.mode().frequency(),
                        ArrayGeometry.PRIOR_VELOCITY_ERROR);
        error *= ScannerUpgrade.velocityFactor(input.level());
        return input.wellTie() ? Math.min(error, 0.04D) : error;
    }

    public static BodyView view(SurveyInput input, SubsurfaceBody body, double velocityError) {
        RockProperties host = NaturalGeology.host(input.setting(), body.y());
        double depth = Math.max(1.0D, input.datumY() - body.y());
        double wavelength = SeismicMath.wavelength(host.velocity(), input.mode().frequency());
        double fresnel = SeismicMath.fresnelRadius(wavelength, depth);
        ArrayView array = arrayAt(input, body.x(), body.z());
        double sigma =
                ArrayGeometry.lateralSigma(
                        fresnel, array.geophoneReceivers(), array.coverage(), input.pixelSize());
        long seed = SeedHash.combine(input.noiseSeed(), body.key());
        double positionError =
                POSITION_ERROR_SCALE * ScannerUpgrade.positionFactor(input.level()) * sigma;
        double x = body.x() + positionError * SeedHash.gaussian(seed, 11);
        double z = body.z() + positionError * SeedHash.gaussian(seed, 12);
        double local = ArrayGeometry.depthRelativeError(0.0D, wavelength, depth);
        double factor = depthFactor(input, velocityError) + local * SeedHash.gaussian(seed, 13);
        double clampedFactor = Math.max(0.6D, Math.min(1.4D, factor));
        double top = input.datumY() - (input.datumY() - body.top()) * clampedFactor;
        double bottom = input.datumY() - (input.datumY() - body.bottom()) * clampedFactor;
        double centre = input.datumY() - (input.datumY() - body.y()) * clampedFactor;
        // A velocity error can push a deep body past the bottom of the record; the whole body is
        // moved back in, keeping its thickness, so its top and base never merge and cancel.
        double shift = Math.max(0.0D, input.minY() + 0.5D - bottom);
        shift -= Math.max(0.0D, top + shift - (input.datumY() - 0.5D));
        return new BodyView(
                body,
                x,
                z,
                top + shift,
                bottom + shift,
                centre + shift,
                wavelength,
                SeismicMath.absorption(
                        input.mode().frequency(), depth, host.velocity(), host.quality()));
    }

    /**
     * Depth scale of the survey's velocity model: a wrong velocity stretches or squeezes every
     * depth of one survey the same way, which is why independent surveys average it out.
     */
    public static double depthFactor(SurveyInput input, double velocityError) {
        return 1.0D
                + velocityError
                        * SeedHash.gaussian(SeedHash.combine(input.noiseSeed(), VELOCITY_SALT), 1);
    }

    /** Interpreted height of a horizon: the survey's depth scale plus a small picking error. */
    public static double horizonY(SurveyInput input, Horizon horizon, double velocityError) {
        double depth = Math.max(1.0D, input.datumY() - horizon.y());
        RockProperties host = NaturalGeology.host(input.setting(), horizon.y() + 1.0D);
        double wavelength = SeismicMath.wavelength(host.velocity(), input.mode().frequency());
        double local = ArrayGeometry.depthRelativeError(0.0D, wavelength, depth);
        double factor =
                depthFactor(input, velocityError)
                        + local
                                * SeedHash.gaussian(
                                        SeedHash.combine(input.noiseSeed(), HORIZON_SALT),
                                        horizon.kind().ordinal());
        return scaledHeight(input, horizon.y(), factor);
    }

    /**
     * Height at {@code factor} times the true depth below the datum, kept inside the modelled range
     * so that a velocity error moves a deep reflector to the bottom instead of out of view.
     */
    private static double scaledHeight(SurveyInput input, double y, double factor) {
        double clamped = Math.max(0.6D, Math.min(1.4D, factor));
        double scaled = input.datumY() - (input.datumY() - y) * clamped;
        return Math.max(input.minY() + 0.5D, Math.min(input.datumY() - 0.5D, scaled));
    }

    /** Runs the survey. Returns {@code null} when {@code cancelled} reports true. */
    public static SurveyMap map(SurveyInput input, BooleanSupplier cancelled) {
        Output output = run(input, cancelled);
        return output == null ? null : output.map();
    }

    /** Runs the survey and attributes its events. Returns {@code null} when cancelled. */
    public static Output run(SurveyInput input, BooleanSupplier cancelled) {
        int resolution = input.resolution();
        int pixels = resolution * resolution;
        int bins = input.bins();
        double binHeight = input.binHeight();
        double pixelSize = input.pixelSize();
        double frequency = input.mode().frequency();
        double velocityError = velocityError(input);

        boolean[] inside = new boolean[pixels];
        double[] focusing = new double[pixels];
        double[] receivers = new double[pixels];
        double[] coverage = new double[pixels];
        double radiusSquared = input.radius() * input.radius();
        for (int row = 0; row < resolution; row++) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            for (int column = 0; column < resolution; column++) {
                int index = column + row * resolution;
                double x = input.pixelX(column);
                double z = input.pixelZ(row);
                double dx = x - input.centerX();
                double dz = z - input.centerZ();
                if (dx * dx + dz * dz > radiusSquared) {
                    continue;
                }
                inside[index] = true;
                ArrayView array = arrayAt(input, x, z);
                focusing[index] = array.focusing();
                receivers[index] = array.allReceivers();
                coverage[index] = array.coverage();
            }
        }

        double[] binY = new double[bins];
        double[] binDepth = new double[bins];
        double[] fresnelSquared = new double[bins];
        double[] absorption = new double[bins];
        double[] wavelength = new double[bins];
        for (int bin = 0; bin < bins; bin++) {
            binY[bin] = input.datumY() - (bin + 0.5D) * binHeight;
            binDepth[bin] = input.datumY() - binY[bin];
            RockProperties host = NaturalGeology.host(input.setting(), binY[bin]);
            wavelength[bin] = SeismicMath.wavelength(host.velocity(), frequency);
            double fresnel = SeismicMath.fresnelRadius(wavelength[bin], binDepth[bin]);
            fresnelSquared[bin] = fresnel * fresnel;
            absorption[bin] =
                    SeismicMath.absorption(
                            frequency, binDepth[bin], host.velocity(), host.quality());
        }

        float[] signal = new float[pixels * bins];
        float[] attenuation = new float[pixels * bins];
        Attribution attribution = new Attribution(pixels * bins);
        double quantisation = pixelSize * pixelSize / 12.0D;

        List<SubsurfaceBody> bodies = input.bodies();
        for (int bodyIndex = 0; bodyIndex < bodies.size(); bodyIndex++) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            SubsurfaceBody body = bodies.get(bodyIndex);
            attribution.current = bodyIndex;
            BodyView view = view(input, body, velocityError);
            double absorptionRatio = Math.max(0.0D, body.attenuationRatio() - 1.0D);
            if (view.thin()) {
                double strength = body.reflectivity() * view.thinFactor() * view.absorption();
                addBody(
                        input,
                        view,
                        binOf(input, view.centerY(), bins),
                        strength,
                        absorptionRatio * view.thinFactor(),
                        inside,
                        focusing,
                        fresnelSquared,
                        quantisation,
                        signal,
                        attenuation,
                        attribution);
            } else {
                double strength = body.reflectivity() * view.absorption();
                addBody(
                        input,
                        view,
                        binOf(input, view.topY(), bins),
                        strength,
                        absorptionRatio,
                        inside,
                        focusing,
                        fresnelSquared,
                        quantisation,
                        signal,
                        attenuation,
                        attribution);
                addBody(
                        input,
                        view,
                        binOf(input, view.bottomY(), bins),
                        -strength,
                        absorptionRatio,
                        inside,
                        focusing,
                        fresnelSquared,
                        quantisation,
                        signal,
                        attenuation,
                        attribution);
            }
        }

        for (Horizon horizon : input.horizons()) {
            int bin = binOf(input, horizonY(input, horizon, velocityError), bins);
            if (bin < 0) {
                continue;
            }
            float value = (float) (horizon.reflectivity() * absorption[bin]);
            for (int index = 0; index < pixels; index++) {
                if (inside[index]) {
                    signal[index * bins + bin] += value;
                }
            }
        }

        double medianFocusing = median(focusing, inside);
        float[] scattering = new float[pixels * bins];
        CavityGrid cavities = input.cavities();
        if (cavities != null && cavities.bins() == bins) {
            for (int bin = 0; bin < bins; bin++) {
                if (cancelled.getAsBoolean()) {
                    return null;
                }
                int window = Math.max(1, (int) Math.round(wavelength[bin] * 0.25D / binHeight));
                // The void fraction a wave averages is set by its Fresnel zone alone; geophones
                // focus the image, not the volume the wave samples.
                double sigma = Math.sqrt(fresnelSquared[bin] + quantisation);
                // Only a change of impedance with depth reflects: the bin answers with the jump
                // from the quarter wavelength above it to the quarter wavelength below, so a
                // uniformly cavernous level reflects at its top and base, not throughout.
                float[] above = cavities.smoothedRange(bin - window, bin - 1, sigma);
                float[] below = cavities.smoothedRange(bin, bin + window - 1, sigma);
                double samplesAbove = cavities.effectiveSamples(bin - window, bin - 1, sigma);
                double samplesBelow = cavities.effectiveSamples(bin, bin + window - 1, sigma);
                for (int row = 0; row < resolution; row++) {
                    for (int column = 0; column < resolution; column++) {
                        int index = column + row * resolution;
                        if (!inside[index]) {
                            continue;
                        }
                        double x = input.pixelX(column);
                        double z = input.pixelZ(row);
                        double fraction = cavities.at(below, x, z);
                        double previous = cavities.at(above, x, z);
                        double jump =
                                significantJump(fraction, previous, samplesBelow, samplesAbove);
                        double reflectivity =
                                SeismicMath.VOID_REFLECTIVITY_PER_FRACTION * jump * absorption[bin];
                        double coherent = reflectivity * CAVITY_COHERENCE;
                        signal[index * bins + bin] += (float) coherent;
                        attribution.offer(index * bins + bin, coherent, SOURCE_CAVITY);
                        scattering[index * bins + bin] =
                                (float)
                                        (SeismicMath.voidReflectivity(fraction)
                                                * absorption[bin]
                                                * CAVITY_SCATTERING);
                        attenuation[index * bins + bin] +=
                                (float) (fraction * SeismicMath.VOID_SCATTERING_PER_FRACTION);
                    }
                }
            }
        }

        // Record: add each trace's noise. Same configuration, same noise.
        float[] noiseSigma = new float[pixels * bins];
        for (int index = 0; index < pixels; index++) {
            if (!inside[index]) {
                continue;
            }
            long pixelSeed = SeedHash.combine(input.noiseSeed() ^ NOISE_SALT, index);
            for (int bin = 0; bin < bins; bin++) {
                double ambient =
                        SeismicMath.noiseSigma(
                                        input.mode().baseNoise(),
                                        binDepth[bin],
                                        input.environment(),
                                        receivers[index])
                                * ScannerUpgrade.noiseFactor(input.level(), binDepth[bin]);
                double scatter = scattering[index * bins + bin];
                double sigma = Math.sqrt(ambient * ambient + scatter * scatter);
                noiseSigma[index * bins + bin] = (float) sigma;
                signal[index * bins + bin] += (float) (sigma * SeedHash.gaussian(pixelSeed, bin));
            }
        }

        // Regional background: the per-depth median over the area. It removes the layered
        // horizons and the average cave porosity; what remains are lateral differences.
        double[] column = new double[pixels];
        for (int bin = 0; bin < bins; bin++) {
            int count = 0;
            for (int index = 0; index < pixels; index++) {
                if (inside[index]) {
                    column[count++] = signal[index * bins + bin];
                }
            }
            float background = (float) medianOf(column, count);
            for (int index = 0; index < pixels; index++) {
                if (inside[index]) {
                    signal[index * bins + bin] -= background;
                }
            }
        }

        for (int bin = 0; bin < bins; bin++) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            double sigmaPixels =
                    MIGRATION_APERTURE
                            * Math.sqrt(fresnelSquared[bin] / medianFocusing)
                            / pixelSize;
            migrate(signal, noiseSigma, inside, resolution, bins, bin, sigmaPixels);
            normaliseToBackground(signal, noiseSigma, inside, bins, bin, column);
        }

        float[] amplitude = new float[pixels];
        float[] sigma = new float[pixels];
        short[] depthY = new short[pixels];
        byte[] attenuationOut = new byte[pixels];
        float[] lateral = new float[pixels];
        int[] sources = new int[pixels];
        Arrays.fill(sources, SOURCE_NONE);
        for (int index = 0; index < pixels; index++) {
            if (!inside[index]) {
                continue;
            }
            int best = 0;
            double bestRatio = -1.0D;
            for (int bin = 0; bin < bins; bin++) {
                double residual = signal[index * bins + bin];
                double ratio = Math.abs(residual) / noiseSigma[index * bins + bin];
                if (ratio > bestRatio) {
                    bestRatio = ratio;
                    best = bin;
                }
            }
            amplitude[index] = signal[index * bins + best];
            sigma[index] = noiseSigma[index * bins + best];
            sources[index] = attribution.source(index * bins + best, sigma[index]);
            depthY[index] = (short) Math.round(binY[best]);
            double attenuationValue =
                    attenuation[index * bins + best]
                            + 0.12D
                                    * SeedHash.gaussian(
                                            SeedHash.combine(
                                                    input.noiseSeed() ^ ATTENUATION_SALT, index),
                                            best);
            attenuationOut[index] =
                    (byte)
                            Math.round(
                                    255.0D
                                            * Math.max(
                                                    0.0D,
                                                    Math.min(
                                                            1.0D,
                                                            attenuationValue
                                                                    / ATTENUATION_FULL_SCALE)));
            lateral[index] =
                    (float) Math.sqrt(fresnelSquared[best] / focusing[index] + quantisation);
        }

        SurveyMap map =
                new SurveyMap(
                        input.mode(),
                        input.centerX(),
                        input.centerZ(),
                        input.radius(),
                        resolution,
                        amplitude,
                        sigma,
                        depthY,
                        attenuationOut,
                        lateral,
                        null);
        map =
                map.withMetrics(
                        metrics(
                                input,
                                map,
                                inside,
                                focusing,
                                receivers,
                                coverage,
                                velocityError,
                                quantisation));
        float[] volume = new float[pixels * bins];
        for (int index = 0; index < pixels; index++) {
            if (!inside[index]) {
                continue;
            }
            for (int bin = 0; bin < bins; bin++) {
                int cell = index * bins + bin;
                volume[cell] = noiseSigma[cell] > 0.0F ? signal[cell] / noiseSigma[cell] : 0.0F;
            }
        }
        return new Output(map, sources, volume, bins);
    }

    /** Strongest single contribution to each pixel and depth bin, and who made it. */
    private static final class Attribution {
        private final float[] strength;
        private final float[] total;
        private final int[] source;
        private int current = SOURCE_NONE;

        Attribution(int cells) {
            strength = new float[cells];
            total = new float[cells];
            source = new int[cells];
            Arrays.fill(source, SOURCE_NONE);
        }

        void offer(int cell, double value, int who) {
            float magnitude = (float) Math.abs(value);
            total[cell] += magnitude;
            if (magnitude > strength[cell]) {
                strength[cell] = magnitude;
                source[cell] = who;
            }
        }

        void offer(int cell, double value) {
            offer(cell, value, current);
        }

        int source(int cell, double sigma) {
            return strength[cell] >= ATTRIBUTION_SHARE * total[cell] && strength[cell] >= sigma
                    ? source[cell]
                    : SOURCE_NONE;
        }
    }

    /**
     * The void-fraction jump between two probed averages, shrunk by the Wiener factor Δ²/(Δ²+σ²)
     * where σ² is the sampling variance of the two binomial estimates. A jump the lattice cannot
     * resolve fades out instead of turning probe noise into reflections; a real cave level, much
     * larger than the sampling error, keeps nearly its full size.
     */
    static double significantJump(
            double below, double above, double samplesBelow, double samplesAbove) {
        double jump = below - above;
        if (samplesBelow <= 0.0D || samplesAbove <= 0.0D) {
            return 0.0D;
        }
        double mean = Math.max(0.0D, Math.min(1.0D, (below + above) * 0.5D));
        double variance = mean * (1.0D - mean) * (1.0D / samplesBelow + 1.0D / samplesAbove);
        double square = jump * jump;
        return square + variance <= 0.0D ? 0.0D : jump * square / (square + variance);
    }

    /**
     * Judges each depth bin against its own background variability. Where the geology itself varies
     * more than the noise (a cave-riddled level, the soft Nether rock), the residuals' robust
     * spread exceeds what noise alone gives, and the bin's σ is widened to it: an anomaly is a
     * point that stands out from its surroundings, not merely from the instrument noise.
     */
    private static void normaliseToBackground(
            float[] signal,
            float[] noiseSigma,
            boolean[] inside,
            int bins,
            int bin,
            double[] work) {
        int count = 0;
        for (int index = 0; index < inside.length; index++) {
            if (inside[index]) {
                work[count++] =
                        Math.abs(signal[index * bins + bin]) / noiseSigma[index * bins + bin];
            }
        }
        if (count == 0) {
            return;
        }
        Arrays.sort(work, 0, count);
        double spread =
                work[(int) Math.floor(BACKGROUND_QUANTILE * (count - 1))] / NOISE_QUANTILE_RATIO;
        if (!(spread > 1.0D)) {
            return;
        }
        for (int index = 0; index < inside.length; index++) {
            if (inside[index]) {
                noiseSigma[index * bins + bin] *= (float) spread;
            }
        }
    }

    /**
     * Gaussian lateral summation of one depth bin over the pixels inside the survey, as a
     * normalised convolution so the circle's edge is not darkened. Noise variances are propagated
     * with the squared weights.
     */
    private static void migrate(
            float[] signal,
            float[] noiseSigma,
            boolean[] inside,
            int resolution,
            int bins,
            int bin,
            double sigmaPixels) {
        if (sigmaPixels < 0.35D) {
            return;
        }
        int radius = Math.max(1, (int) Math.ceil(sigmaPixels * 2.5D));
        double[] kernel = new double[radius * 2 + 1];
        for (int i = -radius; i <= radius; i++) {
            kernel[i + radius] = Math.exp(-0.5D * i * i / (sigmaPixels * sigmaPixels));
        }
        int pixels = resolution * resolution;
        double[] value = new double[pixels];
        double[] variance = new double[pixels];
        double[] mask = new double[pixels];
        for (int index = 0; index < pixels; index++) {
            if (inside[index]) {
                value[index] = signal[index * bins + bin];
                double s = noiseSigma[index * bins + bin];
                variance[index] = s * s;
                mask[index] = 1.0D;
            }
        }
        double[] summedValue =
                blur(
                        blur(value, resolution, kernel, false, true),
                        resolution,
                        kernel,
                        false,
                        false);
        double[] summedVariance =
                blur(
                        blur(variance, resolution, kernel, true, true),
                        resolution,
                        kernel,
                        true,
                        false);
        double[] weights =
                blur(blur(mask, resolution, kernel, false, true), resolution, kernel, false, false);
        for (int index = 0; index < pixels; index++) {
            if (!inside[index] || weights[index] <= 0.0D) {
                continue;
            }
            signal[index * bins + bin] = (float) (summedValue[index] / weights[index]);
            noiseSigma[index * bins + bin] =
                    (float) (Math.sqrt(summedVariance[index]) / weights[index]);
        }
    }

    private static double[] blur(
            double[] source,
            int resolution,
            double[] kernel,
            boolean squaredKernel,
            boolean alongColumns) {
        int radius = kernel.length / 2;
        double[] result = new double[source.length];
        for (int row = 0; row < resolution; row++) {
            for (int column = 0; column < resolution; column++) {
                double sum = 0.0D;
                for (int k = -radius; k <= radius; k++) {
                    int c = alongColumns ? column + k : column;
                    int r = alongColumns ? row : row + k;
                    if (c < 0 || r < 0 || c >= resolution || r >= resolution) {
                        continue;
                    }
                    double w = kernel[k + radius];
                    sum += (squaredKernel ? w * w : w) * source[c + r * resolution];
                }
                result[column + row * resolution] = sum;
            }
        }
        return result;
    }

    private static void addBody(
            SurveyInput input,
            BodyView view,
            int bin,
            double strength,
            double absorptionAnomaly,
            boolean[] inside,
            double[] focusing,
            double[] fresnelSquared,
            double quantisation,
            float[] signal,
            float[] attenuation,
            Attribution attribution) {
        if (bin < 0) {
            return;
        }
        int resolution = input.resolution();
        int bins = input.bins();
        SubsurfaceBody body = view.body();
        double widestSigma = Math.sqrt(fresnelSquared[bin] + quantisation);
        double reach = body.maximumRadius() * 1.5D + widestSigma * 3.5D;
        double pixelSize = input.pixelSize();
        double originX = input.centerX() - input.radius();
        double originZ = input.centerZ() - input.radius();
        int firstColumn = Math.max(0, (int) Math.floor((view.x() - reach - originX) / pixelSize));
        int lastColumn =
                Math.min(
                        resolution - 1, (int) Math.floor((view.x() + reach - originX) / pixelSize));
        int firstRow = Math.max(0, (int) Math.floor((view.z() - reach - originZ) / pixelSize));
        int lastRow =
                Math.min(
                        resolution - 1, (int) Math.floor((view.z() + reach - originZ) / pixelSize));
        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = firstColumn; column <= lastColumn; column++) {
                int index = column + row * resolution;
                if (!inside[index]) {
                    continue;
                }
                double sigma = Math.sqrt(fresnelSquared[bin] / focusing[index] + quantisation);
                double weight =
                        SeismicMath.footprintWeight(
                                input.pixelX(column) - view.x(),
                                input.pixelZ(row) - view.z(),
                                body.radiusX(),
                                body.radiusZ(),
                                body.angle(),
                                sigma);
                if (weight < 1.0E-5D) {
                    continue;
                }
                signal[index * bins + bin] += (float) (strength * weight);
                attribution.offer(index * bins + bin, strength * weight);
                attenuation[index * bins + bin] += (float) (absorptionAnomaly * weight);
            }
        }
    }

    /** Depth bin containing height {@code y}, or -1 outside the modelled range. */
    public static int binOf(SurveyInput input, double y, int bins) {
        int bin = (int) Math.floor((input.datumY() - y) / input.binHeight());
        return bin >= 0 && bin < bins ? bin : -1;
    }

    private static SurveyMetrics metrics(
            SurveyInput input,
            SurveyMap map,
            boolean[] inside,
            double[] focusing,
            double[] receivers,
            double[] coverage,
            double velocityError,
            double quantisation) {
        double referenceY = input.datumY() - SurveyMetrics.REFERENCE_DEPTH;
        RockProperties host = NaturalGeology.host(input.setting(), referenceY);
        double wavelength = SeismicMath.wavelength(host.velocity(), input.mode().frequency());
        double fresnel = SeismicMath.fresnelRadius(wavelength, SurveyMetrics.REFERENCE_DEPTH);
        double medianFocusing = median(focusing, inside);
        double medianReceivers = median(receivers, inside);
        double medianCoverage = median(coverage, inside);
        double lateral = Math.sqrt(fresnel * fresnel / medianFocusing + quantisation);
        double noise =
                SeismicMath.noiseSigma(
                                input.mode().baseNoise(),
                                SurveyMetrics.REFERENCE_DEPTH,
                                input.environment(),
                                medianReceivers)
                        * ScannerUpgrade.noiseFactor(input.level(), SurveyMetrics.REFERENCE_DEPTH);
        double depthError =
                ArrayGeometry.depthRelativeError(
                        velocityError, wavelength, SurveyMetrics.REFERENCE_DEPTH);
        double quality =
                Math.exp(-noise / (2.0D * input.mode().baseNoise()))
                        * (0.55D + 0.45D * medianCoverage)
                        * (1.0D - 0.5D * Math.min(1.0D, depthError / 0.3D));
        double peak = 0.0D;
        for (int index = 0; index < map.size(); index++) {
            peak = Math.max(peak, map.intensity(index));
        }
        int anomalies = SurveyAnomalies.find(map, null).size();
        return new SurveyMetrics(
                input.geophoneX().length,
                medianReceivers,
                medianCoverage,
                velocityError,
                depthError,
                lateral,
                noise,
                Math.max(0.0D, Math.min(1.0D, quality)),
                peak,
                anomalies);
    }

    private static double median(double[] values, boolean[] mask) {
        double[] selected = new double[values.length];
        int count = 0;
        for (int i = 0; i < values.length; i++) {
            if (mask[i]) {
                selected[count++] = values[i];
            }
        }
        return count == 0 ? 1.0D : medianOf(selected, count);
    }

    static double medianOf(double[] values, int count) {
        if (count <= 0) {
            return 0.0D;
        }
        double[] copy = Arrays.copyOf(values, count);
        Arrays.sort(copy);
        return count % 2 == 1 ? copy[count / 2] : (copy[count / 2 - 1] + copy[count / 2]) * 0.5D;
    }
}
