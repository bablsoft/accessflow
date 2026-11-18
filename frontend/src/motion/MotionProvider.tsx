import type { ReactNode } from 'react';
import { LazyMotion, MotionConfig } from 'motion/react';
import { transitions } from './transitions';

const loadFeatures = () => import('./features').then((mod) => mod.default);

/**
 * App-wide Motion setup: the lazy feature bundle (animate with `m` from `motion/react-m`, not
 * `motion`), the default transition, and reduced motion following the OS setting.
 */
export function MotionProvider({ children }: { children: ReactNode }) {
  return (
    <LazyMotion features={loadFeatures} strict>
      <MotionConfig reducedMotion="user" transition={transitions.enter}>
        {children}
      </MotionConfig>
    </LazyMotion>
  );
}
