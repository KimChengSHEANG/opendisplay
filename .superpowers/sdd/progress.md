# SDD Progress — Sunshine FEC + IDR Recovery

Branch: sunshine-udp-android
Plan: superpowers/plans/2026-07-28-sunshine-fec-idr-recovery.md
Merge-base: b34cb438b2a12309596d01d0fe5337e77640cb48
Started-from: fa6643c (plan commit)

## Tasks
Task 1: complete — Spec + notes
Task 2: complete — qos incompleteRate + health policy
Task 3: complete — ReceiverSession drop NACK, IDR-only
Task 4: complete — Reorder window
Task 5: complete — Mac remove retransmit; AIMD incompleteRate
Task 6: complete — Docs + Mac UI copy
Task 7: complete — Full verification gate

## Minor findings (for final review)
(none yet)

Task 1: complete (commits fa6643c..e0f96a6, review clean)
Task 2: complete (commits e0f96a6..6e11628, review clean)
Task 3: complete (commits 6e11628..1212f7f, review clean)
Minor: Task3 handleUdpIncomplete need||firstForFrame can re-send kf while awaiting keyframe for same frameId
Task 4: complete (commits 1212f7f..3ba5172, review clean)
Task 5: complete (commits 3ba5172..a13d8be, review clean)
Minor: plan-mandated AIMD Kotlin/Swift duplication (VideoRatePolicy mirror)
Minor: VideoRatePolicyTest missing decrease-branch coverage
Task 6: complete (commits a13d8be..e2289fb, review clean)
Minor: root README.md still mentions FEC, NACK (out of Task 6 file list)
Task 7: complete (commits e2289fb..491ac96, review clean; Chromebook smoke SKIPPED)
Final review: Ready to merge (Yes). Minors only; Chromebook smoke skipped.
