export const sleep = (ms: number): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, ms));

export const jittered = (min = 200, max = 500): Promise<void> =>
  sleep(min + Math.random() * (max - min));
