import axios from 'axios';

// In demo mode, the api/* modules call mock handlers directly. This Axios
// instance is wired up so that AF-FE-01 can swap mock-backed implementations
// for real HTTP calls without touching call sites.
export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080',
  withCredentials: true,
});
