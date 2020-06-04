package edu.washington.cs.ubicomp.vlc;

/*
 *      _______                       _____   _____ _____
 *     |__   __|                     |  __ \ / ____|  __ \
 *        | | __ _ _ __ ___  ___  ___| |  | | (___ | |__) |
 *        | |/ _` | '__/ __|/ _ \/ __| |  | |\___ \|  ___/
 *        | | (_| | |  \__ \ (_) \__ \ |__| |____) | |
 *        |_|\__,_|_|  |___/\___/|___/_____/|_____/|_|
 *
 * -------------------------------------------------------------
 *
 * TarsosDSP is developed by Joren Six at IPEM, University Ghent
 *
 * -------------------------------------------------------------
 *
 *  Info: http://0110.be/tag/TarsosDSP
 *  Github: https://github.com/JorenSix/TarsosDSP
 *  Releases: http://0110.be/releases/TarsosDSP/
 *
 *  TarsosDSP includes modified source code by various authors,
 *  for credits and info, see README.
 *
 */


import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * Contains an implementation of the Goertzel algorithm. It can be used to
 * detect if one or more predefined frequencies are present in a signal. E.g. to
 * do DTMF decoding.
 *
 * @author Joren Six
 */
public class Goertzel {

    /**
     * If the power in dB is higher than this threshold, the frequency is
     * present in the signal.
     */
    private static final double POWER_THRESHOLD = 30;// in dB

    /**
     * A list of frequencies to detect.
     */
    private final double[] frequenciesToDetect;

    public double[] detectedFrequencies;
    public double[] detectedPower;

    /**
     * Cached cosine calculations for each frequency to detect.
     */
    private final double[] precalculatedCosines;
    /**
     * Cached wnk calculations for each frequency to detect.
     */
    private final double[] precalculatedWnk;
    /**
     * A calculated power for each frequency to detect. This array is reused for
     * performance reasons.
     */

    private double sampleRate;
    private final double[] calculatedPowers;

    public Goertzel(final float sampleRate,
                    double[] frequencies) {

        frequenciesToDetect = frequencies;
        precalculatedCosines = new double[frequencies.length];
        precalculatedWnk = new double[frequencies.length];

        calculatedPowers = new double[frequencies.length];

        this.sampleRate = sampleRate;
    }

    /**
    public boolean process(double[] doubleBuffer) {
        int window_size = doubleBuffer.length;
        double f_step = sampleRate / (double)window_size;
        double f_step_normalized = 1.0 / (double)window_size;

        HashSet bins = new HashSet();
        for (f_range in freqs):
        f_start, f_end = f_range
        k_start = int(math.floor(f_start / f_step))
        k_end = int(math.ceil(f_end / f_step))

        if k_end > window_size - 1: raise ValueError('frequency out of range %s' % k_end)
        bins = bins.union(range(k_start, k_end))

    # For all the bins, calculate the DFT term
                n_range = range(0, window_size)
        freqs = []
        results = []
        for k in bins:

        # Bin frequency and coefficients for the computation
        f = k * f_step_normalized
        print('f', k, f,f_step_normalized)
        w_real = 2.0 * math.cos(2.0 * math.pi * f)
        w_imag = math.sin(2.0 * math.pi * f)

        # Doing the calculation on the whole sample
                d1, d2 = 0.0, 0.0
        for n in n_range:
        y  = samples[n] + w_real * d1 - d2
        d2, d1 = d1, y

        # Storing results `(real part, imag part, power)`
        results.append((
                        0.5 * w_real * d1 - d2, w_imag * d1,
                d2**2 + d1**2 - w_real * d1 * d2)
        )
        freqs.append(f * sample_rate)
        return freqs, results
    }*/

    public boolean process(double[] doubleBuffer) {
        for (int i = 0; i < frequenciesToDetect.length; i++) {
            precalculatedCosines[i] = 2 * Math.cos(2 * Math.PI
                    * frequenciesToDetect[i] / doubleBuffer.length);
            precalculatedWnk[i] = Math.exp(-2 * Math.PI
                    * frequenciesToDetect[i] / doubleBuffer.length);
        }

        double skn0, skn1, skn2;
        int numberOfDetectedFrequencies = 0;
        for (int j = 0; j < frequenciesToDetect.length; j++) {
            skn0 = skn1 = skn2 = 0;
            for (int i = 0; i < doubleBuffer.length; i++) {
                skn2 = skn1;
                skn1 = skn0;
                skn0 = precalculatedCosines[j] * skn1 - skn2
                        + doubleBuffer[i];
            }
            double wnk = precalculatedWnk[j];
            calculatedPowers[j] = 20 * Math.log10(Math.abs(skn0 - wnk * skn1));
            if (calculatedPowers[j] > POWER_THRESHOLD) {
                numberOfDetectedFrequencies++;
            }
        }

        if (numberOfDetectedFrequencies > 0) {
            detectedFrequencies = new double[numberOfDetectedFrequencies];
            detectedPower = new double[numberOfDetectedFrequencies];
            int index = 0;
            for (int j = 0; j < frequenciesToDetect.length; j++) {
                if (calculatedPowers[j] > POWER_THRESHOLD) {
                    Log.d("VLC", "Detected Peak - inner " + j);
                    detectedFrequencies[index] = frequenciesToDetect[j];
                    detectedPower[index] = calculatedPowers[j];
                    index++;
                }
            }
        }

        return true;
    }
}
