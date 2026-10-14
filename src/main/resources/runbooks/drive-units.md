# Drive-unit runbook

## MOTOR_OVERTEMP
Severity SEV1. A drive motor has crossed the 85 C hard limit and the unit may derate or trip its thermal cut-out mid-aisle.
1. Command the robot to stop and park in the nearest maintenance lane (fleet console: Stop and park).
2. Check whether it was carrying a pod near the payload limit: an overloaded pod is the most common cause.
3. Look at the last 10 minutes of motor temperature and wheel current. A rising current with rising temperature points to a dragging brake or failing bearing.
4. Do not return the unit to service until it is below 60 C and a technician has inspected the brake and the wheel module.
5. If three or more units on the same floor overheat within an hour, escalate to facilities and check the floor temperature and HVAC.

## MOTOR_TEMP_DRIFT
Severity SEV3, ticket only. The motor is running hotter than its own baseline but is still below the hard limit. Usually an early sign of bearing wear, debris in the wheel well, or a slowly dragging brake.
1. Schedule an inspection at the next charging stop; no immediate action is needed.
2. Compare wheel current: if current is also drifting up, prioritise a wheel-module inspection.
3. Clear debris from the wheel well and re-check after 30 minutes of operation.

## WHEEL_CURRENT_DRIFT
Severity SEV3. Wheel motor current is far from this unit's baseline. High current means mechanical resistance (debris, a damaged tyre, a dragging brake or a floor defect). Low current under load can mean a slipping wheel or an encoder fault.
1. Check the robot's recent positions: if the drift is tied to one floor area, inspect the floor there for spills, damaged tiles or debris.
2. If it is tied to the unit, inspect the tyres and wheel module.

## DRIVE_STUCK
Severity SEV2. The robot is commanded to move but has not moved for 20 seconds or more. It blocks an aisle and the fleet routes around it, which costs throughput.
1. Check for an obstruction at the reported position (fallen item, debris or another unit) using the floor camera.
2. Check the error codes for a bumper contact (W301) or a lidar fault (E101).
3. If the area is clear, soft-reset the drive from the fleet console. If it is still stuck, send a floor associate with the amnesty procedure and recover the unit manually.

## BATTERY_CRITICAL
Severity SEV2. Battery is at or below 8% while not charging; the unit may shut down in an aisle.
1. Send the robot to the nearest charger immediately; the fleet planner should pre-empt its current task.
2. If the unit is not heading to a charger, check charger availability on the floor. A full or faulted charger bank causes queueing.
3. Repeated critical-battery events on one unit point to battery degradation: open a battery-health ticket.

## ERROR_E101
Lidar or safety-scanner fault. The unit stops for safety. Clean the scanner window, power-cycle the drive, and replace the scanner if the fault recurs.

## ERROR_E204
Wheel encoder mismatch: the left and right odometry disagree. Localisation can drift. Take the unit out of service and recalibrate the encoders.

## ERROR_W301
Bumper contact. Usually a minor collision with a pod or debris. Inspect the area for damage; the unit resumes automatically when the bumper clears.

# Platform runbook

## INGEST_DLQ_NOT_EMPTY
Messages landed in the telemetry dead-letter queue after 5 failed processing attempts.
1. Read a sample message from the DLQ in the SQS console and check the processor logs for its message id.
2. Fix the cause (bad deploy, DynamoDB throttling, schema change), then redrive the DLQ to the source queue.

## PROCESSOR_ERRORS
The processor Lambda is throwing errors. Check CloudWatch Logs Insights for the stack trace, check DynamoDB throttling metrics, and roll back the last deployment if errors started with it.

## INGEST_LAG
Heartbeats are taking too long to reach the processor. Check the SQS ApproximateAgeOfOldestMessage metric and the Lambda concurrency and throttles; raise reserved concurrency if the queue is backing up.
