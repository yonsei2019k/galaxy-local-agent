//! An owned child tree only. Never enumerates, targets or terminates unrelated user processes.
use crate::{error, Result};
use tokio::process::Child;

#[cfg(windows)]
mod windows {
    use super::*;
    use std::ffi::c_void;
    type Handle = *mut c_void;
    #[repr(C)]
    #[derive(Default)]
    struct BasicLimits {
        process_time: i64,
        job_time: i64,
        flags: u32,
        min_working_set: usize,
        max_working_set: usize,
        active_process_limit: u32,
        affinity: usize,
        priority: u32,
        scheduling: u32,
    }
    #[repr(C)]
    #[derive(Default)]
    struct ExtendedLimits {
        basic: BasicLimits,
        io_counters: [u64; 6],
        process_memory: usize,
        job_memory: usize,
        peak_process_memory: usize,
        peak_job_memory: usize,
    }
    #[link(name = "kernel32")]
    extern "system" {
        fn CreateJobObjectW(attributes: *const c_void, name: *const u16) -> Handle;
        fn SetInformationJobObject(
            job: Handle,
            class: i32,
            information: *const c_void,
            size: u32,
        ) -> i32;
        fn AssignProcessToJobObject(job: Handle, process: Handle) -> i32;
        fn TerminateJobObject(job: Handle, code: u32) -> i32;
        fn CloseHandle(handle: Handle) -> i32;
    }
    pub struct ProcessTree {
        job: isize,
    }
    impl ProcessTree {
        pub fn attach(child: &Child) -> Result<Self> {
            let process = child
                .raw_handle()
                .ok_or_else(|| error("PROCESS_TREE_HANDLE"))?;
            // No inherited job handle. Descendants join the job automatically; closing our
            // handle kills them even if the CLI exits before its MCP/helper processes do.
            let job = unsafe { CreateJobObjectW(std::ptr::null(), std::ptr::null()) };
            if job.is_null() {
                return Err(error("PROCESS_TREE_CREATE"));
            }
            let mut limits = ExtendedLimits::default();
            limits.basic.flags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            let configured = unsafe {
                SetInformationJobObject(
                    job,
                    9,
                    (&limits as *const ExtendedLimits).cast(),
                    std::mem::size_of::<ExtendedLimits>() as u32,
                ) != 0
                    && AssignProcessToJobObject(job, process.cast()) != 0
            };
            if !configured {
                unsafe {
                    CloseHandle(job);
                }
                return Err(error("PROCESS_TREE_ASSIGN"));
            }
            Ok(Self { job: job as isize })
        }
        pub fn terminate(&mut self) {
            unsafe {
                TerminateJobObject(self.job as Handle, 1);
            }
        }
    }
    impl Drop for ProcessTree {
        fn drop(&mut self) {
            unsafe {
                CloseHandle(self.job as Handle);
            }
        }
    }
}

#[cfg(windows)]
pub use windows::ProcessTree;

#[cfg(unix)]
pub struct ProcessTree {
    group: Option<i32>,
}
#[cfg(unix)]
impl ProcessTree {
    pub fn attach(child: &Child) -> Result<Self> {
        Ok(Self {
            group: Some(child.id().ok_or_else(|| error("PROCESS_TREE_HANDLE"))? as i32),
        })
    }
    pub fn terminate(&mut self) {
        unsafe extern "C" {
            fn kill(pid: i32, signal: i32) -> i32;
        }
        if let Some(group) = self.group.take() {
            unsafe {
                kill(-group, 9);
            }
        }
    }
}
#[cfg(unix)]
impl Drop for ProcessTree {
    fn drop(&mut self) {
        self.terminate();
    }
}
