import type { ReactNode } from 'react';
import { LazyMotion, MotionConfig, domAnimation } from 'motion/react';
import { transitions } from './transitions';

/**
 * App-wide Motion setup: the lazy feature bundle (animate with `m` from `motion/react-m`, not
 * `motion`), the default transition, and reduced motion following the OS setting.
 */
export function MotionProvider({ children }: { children: ReactNode }) {
  return (
    <LazyMotion features={domAnimation} strict>
      <MotionConfig reducedMotion="user" transition={transitions.enter}>
        {children}
      </MotionConfig>
    </LazyMotion>
  );
}
