//! Position estimation by multilateration with robust algorithms.

#![allow(non_snake_case)]

use android_logger::Config;
use jni::JNIEnv;
use jni::objects::{JClass, JObjectArray};
use jni::sys::jobject;
use log::{debug, error, info};
use rand::{Rng, rngs::SmallRng, SeedableRng};
use std::ops::Sub;

/// Performs multilateration.
// SAFETY: There is no other global function of this name.
#[no_mangle]
pub extern "system" fn Java_app_grapheneos_networklocation_interop_multilateration_Multilateration_multilateration(
    mut env: JNIEnv,
    _class: JClass,
    measurements: JObjectArray,
) -> jobject {
    android_logger::init_once(
        Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("RustMultilateration"),
    );

    debug!("Starting multilateration function...");

    let len = match env.get_array_length(&measurements) {
        Ok(len) => len as usize,
        Err(e) => {
            error!("Failed to get array length: {:?}", e);
            return std::ptr::null_mut();
        }
    };

    debug!("Number of measurements: {}", len);

    let mut measurements_converted: Vec<Measurement> = Vec::with_capacity(len);

    let position_class_path = "app/grapheneos/networklocation/interop/multilateration/Position";

    for i in 0..len {
        let measurement_obj = match env.get_object_array_element(&measurements, i as i32) {
            Ok(obj) => obj,
            Err(e) => {
                error!("Failed to get object array element at index {}: {:?}", i, e);
                return std::ptr::null_mut();
            }
        };

        debug!("Successfully retrieved measurement object at index {}", i);

        let distance = match env.get_field(&measurement_obj, "distance", "D") {
            Ok(field) => match field.d() {
                Ok(value) => value,
                Err(e) => {
                    error!("Failed to convert distance field to f64: {:?}", e);
                    return std::ptr::null_mut();
                }
            },
            Err(e) => {
                error!("Failed to get distance field: {:?}", e);
                return std::ptr::null_mut();
            }
        };

        debug!("Measurement at index {} has distance: {}", i, distance);

        let pos_obj = match env.get_field(
            &measurement_obj,
            "position",
            "Lapp/grapheneos/networklocation/interop/multilateration/Position;",
        ) {
            Ok(field) => match field.l() {
                Ok(obj) => obj,
                Err(e) => {
                    error!("Failed to convert position field to object: {:?}", e);
                    return std::ptr::null_mut();
                }
            },
            Err(e) => {
                error!("Failed to get position field: {:?}", e);
                return std::ptr::null_mut();
            }
        };

        let x = env
            .call_method(&pos_obj, "getX", "()D", &[])
            .unwrap()
            .d()
            .unwrap();
        let y = env
            .call_method(&pos_obj, "getY", "()D", &[])
            .unwrap()
            .d()
            .unwrap();
        let z = env
            .call_method(&pos_obj, "getZ", "()D", &[])
            .unwrap()
            .d()
            .unwrap();
        let xy_variance = env
            .call_method(&pos_obj, "getXyVariance", "()D", &[])
            .unwrap()
            .d()
            .unwrap();
        let z_variance = env
            .call_method(&pos_obj, "getZVariance", "()D", &[])
            .unwrap()
            .d()
            .unwrap();

        let pos = Position {
            x,
            y,
            z,
            xy_variance,
            z_variance,
        };

        measurements_converted.push(Measurement {
            position: pos,
            distance,
            probability: 0.0, // will be computed in the algorithm
        });
    }

    let estimated_position = multilateration(&mut measurements_converted);

    let pos_class = env.find_class(position_class_path).unwrap();
    let ret_obj = env
        .new_object(
            pos_class,
            "(DDDDD)V",
            &[
                estimated_position.x.into(),
                estimated_position.y.into(),
                estimated_position.z.into(),
                estimated_position.xy_variance.into(),
                estimated_position.z_variance.into(),
            ],
        )
        .unwrap();

    ret_obj.into_raw()
}

/**
 * Position.
 */
#[derive(Clone, Copy, Default, Debug)]
pub struct Position {
    /// x
    pub x: f64,
    /// y
    pub y: f64,
    /// z
    pub z: f64,
    /// variance in xy plane (1-sigma²)
    pub xy_variance: f64,
    /// variance in z direction (1-sigma²)
    pub z_variance: f64,
}

impl Sub for Position {
    type Output = Self;

    fn sub(self, other: Self) -> Self::Output {
        Self {
            x: self.x - other.x,
            y: self.y - other.y,
            z: self.z - other.z,
            xy_variance: other.xy_variance,
            z_variance: other.z_variance,
        }
    }
}

/**
 * Measurement.
 */
#[derive(Clone, Copy, Default, Debug)]
pub struct Measurement {
    /// position
    pub position: Position,
    /// distance
    pub distance: f64,
    /// probability
    pub probability: f64,
}

/**
 * Multilateration.
 */
pub fn multilateration(measurements: &mut [Measurement]) -> Position {
    let mut random = SmallRng::seed_from_u64(2);

    // TODO: make a better initial guess
    // initial guess
    let mut estimated_position = Position {
        x: random.gen_range(-100.0..100.0),
        y: random.gen_range(-100.0..100.0),
        z: random.gen_range(-100.0..100.0),
        xy_variance: random.gen_range(0.0..100.0),
        z_variance: random.gen_range(0.0..100.0),
    };

    // TODO: determine if the learning rates are useful when enabled (not 1.0)
    //  they might not be good because it causes us to need more iterations
    //  and there might not be a benefit
    let learning_rate = 1.0;
    let variance_learning_rate = 1.0;

    let max_iterations = 10_000;
    let tolerance = 1e-5;
    // minimum probability so that measurements far off from the initial
    // guess still gently pull the estimated position towards the right direction
    let min_probability = 1e-3;
    // expected measurement error (1-sigma) corresponding to a 68% confidence interval
    let sigma: f64 = 100.0;

    let mut completed_iterations = 0;

    for _ in 0..max_iterations {
        completed_iterations += 1;

        // E step: for each measurement, compare difference between estimated position and their position and distance to get a probability
        for measurement in measurements.iter_mut() {
            let x_delta = estimated_position.x - measurement.position.x;
            let y_delta = estimated_position.y - measurement.position.y;
            let z_delta = estimated_position.z - measurement.position.z;

            let estimated_distance = (x_delta.powi(2) + y_delta.powi(2) + z_delta.powi(2)).sqrt();
            let distance_delta = estimated_distance - measurement.distance;

            // Gaussian likelihood:
            // probability = exp(-0.5 * (error/sigma)^2)
            let probability = (-0.5 * (distance_delta.powi(2)) / (sigma.powi(2))).exp();
            measurement.probability = probability.max(min_probability);
        }

        // M step: adjust estimated position to fit the points that are most probable for them
        let mut delta_update = Position::default();
        let mut total_weight_xy = 0.0;
        let mut total_weight_z = 0.0;

        for measurement in measurements.iter() {
            let x_delta = estimated_position.x - measurement.position.x;
            let y_delta = estimated_position.y - measurement.position.y;
            let z_delta = estimated_position.z - measurement.position.z;

            let estimated_distance = (x_delta.powi(2) + y_delta.powi(2) + z_delta.powi(2)).sqrt();
            // distance factor scales the correction from the measurement's reported distance
            let distance_factor = if estimated_distance > 0.0 {
                measurement.distance / estimated_distance
            } else {
                0.0
            };

            let weight_xy = measurement.probability / measurement.position.xy_variance;
            let weight_z = measurement.probability / measurement.position.z_variance;

            delta_update.x += x_delta * (distance_factor - 1.0) * weight_xy;
            delta_update.y += y_delta * (distance_factor - 1.0) * weight_xy;
            delta_update.z += z_delta * (distance_factor - 1.0) * weight_z;

            total_weight_xy += weight_xy;
            total_weight_z += weight_z;
        }

        if total_weight_xy != 0.0 {
            estimated_position.x += learning_rate * delta_update.x / total_weight_xy;
            estimated_position.y += learning_rate * delta_update.y / total_weight_xy;
        }
        if total_weight_z != 0.0 {
            estimated_position.z += learning_rate * delta_update.z / total_weight_z;
        }

        let mut sum_probability = 0.0;
        let mut weighted_error_xy = 0.0;
        let mut weighted_error_z = 0.0;
        for measurement in measurements.iter() {
            let error_x = estimated_position.x - measurement.position.x;
            let error_y = estimated_position.y - measurement.position.y;
            let error_z = estimated_position.z - measurement.position.z;

            weighted_error_xy +=
                measurement.probability * ((error_x.powi(2) + error_y.powi(2)) / 2.0);
            weighted_error_z += measurement.probability * (error_z.powi(2));
            sum_probability += measurement.probability;
        }

        if sum_probability > 0.0 {
            let new_xy_variance = weighted_error_xy / sum_probability;
            let new_z_variance = weighted_error_z / sum_probability;

            estimated_position.xy_variance = variance_learning_rate * new_xy_variance
                + (1.0 - variance_learning_rate) * estimated_position.xy_variance;
            estimated_position.z_variance = variance_learning_rate * new_z_variance
                + (1.0 - variance_learning_rate) * estimated_position.z_variance;
        }

        if delta_update.x < tolerance && delta_update.y < tolerance && delta_update.z < tolerance {
            break;
        }
    }

    info!("completed_iterations: {completed_iterations}");

    estimated_position
}

#[cfg(test)]
mod tests {
    use log::LevelFilter;
    use std::ops::Range;

    use super::*;

    fn generate_random_measurement(
        x_range: Range<f64>,
        y_range: Range<f64>,
        z_range: Range<f64>,
        real_position: Position,
        random: &mut SmallRng,
    ) -> Measurement {
        let x = random.gen_range(x_range);
        let y = random.gen_range(y_range);
        let z = random.gen_range(z_range);
        let xy_variance = random.gen_range(100.0..1000.0);
        let z_variance = random.gen_range(10.0..100.0);
        let distance = ((x - real_position.x).powi(2)
            + (y - real_position.y).powi(2)
            + (z - real_position.z).powi(2))
            .sqrt();

        Measurement {
            position: Position {
                x,
                y,
                z,
                xy_variance,
                z_variance,
            },
            distance,
            ..Default::default()
        }
    }

    fn average_position(
        deltas: impl std::clone::Clone + std::iter::ExactSizeIterator<Item=Position>,
    ) -> Position {
        Position {
            x: deltas.clone().map(|p| p.x).sum::<f64>() / deltas.len() as f64,
            y: deltas.clone().map(|p| p.y).sum::<f64>() / deltas.len() as f64,
            z: deltas.clone().map(|p| p.z).sum::<f64>() / deltas.len() as f64,
            xy_variance: deltas.clone().map(|p| p.xy_variance).sum::<f64>() / deltas.len() as f64,
            z_variance: deltas.clone().map(|p| p.z_variance).sum::<f64>() / deltas.len() as f64,
        }
    }

    #[test]
    fn test_random() {
        env_logger::builder()
            .filter_level(LevelFilter::max())
            .init();

        let mut results = vec![];

        for s in 0..100 {
            let mut random = SmallRng::seed_from_u64(s);

            let real_position = Position {
                x: random.gen_range(-200.0..200.0),
                y: random.gen_range(-200.0..200.0),
                z: random.gen_range(-200.0..200.0),
                xy_variance: random.gen_range(0.0..100.0),
                z_variance: random.gen_range(0.0..100.0),
            };

            let mut measurements = vec![];

            for _ in 0..9 {
                measurements.push(generate_random_measurement(
                    -400.0..400.0,
                    -400.0..400.0,
                    -400.0..400.0,
                    real_position,
                    &mut random,
                ));
            }

            let estimated_position = multilateration(&mut measurements);

            results.push((real_position, estimated_position));
        }

        let position_deltas = results.iter().map(|r| r.0 - r.1);

        let average_delta = average_position(position_deltas);

        info!("average delta: {:#?}", average_delta)
    }

    #[test]
    fn test_unknown_z() {
        env_logger::builder()
            .filter_level(LevelFilter::max())
            .init();

        let mut results = vec![];

        for s in 0..100 {
            let mut random = SmallRng::seed_from_u64(s);

            let real_position = Position {
                x: random.gen_range(-200.0..200.0),
                y: random.gen_range(-200.0..200.0),
                z: random.gen_range(-200.0..200.0),
                xy_variance: random.gen_range(0.0..100.0),
                z_variance: random.gen_range(0.0..100.0),
            };

            let mut measurements = vec![];

            for i in 0..9 {
                let mut measurement = generate_random_measurement(
                    -400.0..400.0,
                    -400.0..400.0,
                    -400.0..400.0,
                    real_position,
                    &mut random,
                );

                // TODO: set it to unknown once it's supported
                if i < 7 {
                    measurement.position.z = 0.0;
                }

                measurements.push(measurement);
            }

            let estimated_position = multilateration(&mut measurements);

            results.push((real_position, estimated_position));
        }

        let position_deltas = results.iter().map(|r| r.0 - r.1);

        let average_delta = average_position(position_deltas);

        info!("average delta: {:#?}", average_delta)
    }
}
