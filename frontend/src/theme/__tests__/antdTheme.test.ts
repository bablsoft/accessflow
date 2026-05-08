import { describe, it, expect } from 'vitest';
import { theme } from 'antd';
import { lightTheme, darkTheme } from '../antdTheme';

describe('antdTheme', () => {
  describe('lightTheme', () => {
    it('uses the default algorithm', () => {
      expect(lightTheme.algorithm).toBe(theme.defaultAlgorithm);
    });

    it('exposes the af cssVar key', () => {
      expect(lightTheme.cssVar).toEqual({ key: 'af' });
    });

    it('defines all four text-tier colors', () => {
      const t = lightTheme.token!;
      expect(t.colorText).toBeTruthy();
      expect(t.colorTextSecondary).toBeTruthy();
      expect(t.colorTextTertiary).toBeTruthy();
      expect(t.colorTextQuaternary).toBeTruthy();
    });
  });

  describe('darkTheme', () => {
    it('uses the dark algorithm', () => {
      expect(darkTheme.algorithm).toBe(theme.darkAlgorithm);
    });

    it('exposes the af cssVar key', () => {
      expect(darkTheme.cssVar).toEqual({ key: 'af' });
    });

    it('defines all four text-tier colors', () => {
      const t = darkTheme.token!;
      expect(t.colorText).toBeTruthy();
      expect(t.colorTextSecondary).toBeTruthy();
      expect(t.colorTextTertiary).toBeTruthy();
      expect(t.colorTextQuaternary).toBeTruthy();
    });

    it('lifts secondary/tertiary/quaternary above the previous low-contrast hexes', () => {
      const t = darkTheme.token!;
      expect(t.colorTextSecondary).not.toBe('#a1a1aa');
      expect(t.colorTextTertiary).not.toBe('#71717a');
      expect(t.colorTextQuaternary).not.toBe('#52525b');
    });
  });
});
