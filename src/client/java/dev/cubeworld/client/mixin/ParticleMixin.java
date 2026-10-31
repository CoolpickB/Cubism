package dev.cubeworld.client.mixin;

import dev.cubeworld.client.SeamParticle;
import dev.cubeworld.seam.SeamInteraction;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Particle.class)
public abstract class ParticleMixin implements SeamParticle {
	@Shadow protected double xo;
	@Shadow protected double yo;
	@Shadow protected double zo;
	@Shadow protected double x;
	@Shadow protected double y;
	@Shadow protected double z;
	@Shadow protected double xd;
	@Shadow protected double yd;
	@Shadow protected double zd;

	@Shadow public abstract void setPos(double x, double y, double z);

	@Override
	public double cubeworld$x() {
		return this.x;
	}

	@Override
	public double cubeworld$z() {
		return this.z;
	}

	@Override
	public void cubeworld$move(int[] motion) {
		Vec3 position = SeamInteraction.move(motion, new Vec3(this.x, this.y, this.z));
		Vec3 old = SeamInteraction.move(motion, new Vec3(this.xo, this.yo, this.zo));
		Vec3 velocity = SeamInteraction.rotate(motion, new Vec3(this.xd, this.yd, this.zd));
		this.setPos(position.x, position.y, position.z);
		this.xo = old.x;
		this.yo = old.y;
		this.zo = old.z;
		this.xd = velocity.x;
		this.yd = velocity.y;
		this.zd = velocity.z;
	}
}
