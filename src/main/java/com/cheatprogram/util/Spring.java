package com.cheatprogram.util;

/** Damped spring; slightly underdamped settings give the soft wobble of a liquid shape settling. */
public final class Spring {
	public float value;
	public float velocity;
	public float target;

	public Spring(float value) {
		this.value = value;
		this.target = value;
	}

	public float update(float dt, float stiffness, float damping) {
		float speed = Anim.globalSpeed();
		float k = stiffness * speed * speed;
		float d = damping * speed;
		float remaining = Math.min(dt, 0.1f);
		while (remaining > 0f) {
			float step = Math.min(remaining, 1f / 240f);
			float accel = (target - value) * k - velocity * d;
			velocity += accel * step;
			value += velocity * step;
			remaining -= step;
		}
		if (Math.abs(target - value) < 0.01f && Math.abs(velocity) < 0.01f) {
			value = target;
			velocity = 0f;
		}
		return value;
	}
}
