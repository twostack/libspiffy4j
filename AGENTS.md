# Engineering Standards

## Fix It Now

When you encounter a problem — fix it. Don't note it for later. Don't dismiss it as pre-existing. Don't add a TODO comment and move on. The codebase is better or worse after every change you make; there is no neutral.

If you find a bug while working on something else, fix the bug. If you see an inconsistency (a function here, a constant there for the same concept), make them consistent. If a test is broken, fix it. "Not my change" is not an engineering principle.

## Root Causes, Not Workarounds

When something doesn't work, find out why and fix the cause. Don't paper over symptoms.

A 12-line `hide` clause that suppresses import collisions is not a fix — it's duct tape over a broken export structure. A `// TODO: clean this up later` is a confession that you chose not to do your job now. An adapter that translates between two types that shouldn't both exist is a sign you have one type too many.

If the architecture is causing the problem, fix the architecture.

## One Thing, Not Two

When you build something that replaces something else, delete the old one. Don't leave both around "for compatibility" or "in case someone needs it." Two guides that cover the same topic is worse than one. Two types with the same name in different files is a design error, not a naming challenge.

If a new file supersedes an old file, the commit that adds the new file should delete the old one.

## Say What You Mean

Name things for what they are, not where they live. A developer guide is a developer guide — not a "coordinator developer guide." A command to create a wallet is `CreateWalletCommand` — not `CoordinatorCreateWalletCommand`. If the context is already clear from the file, module, or import path, the name shouldn't repeat it.

Redundant prefixes are a sign you haven't thought about where the type lives in the system.

## Act, Don't Narrate

Don't explain what you're about to check. Don't list the things that might break. Don't describe the three approaches you could take and ask which one is preferred when one of them is obviously right.

Check it. Fix it. Move on. If something actually does break, deal with it then.

## Consistency Is Not Optional

If two factory methods serve the same purpose (testnet config, mainnet config), they have the same shape. If three storage backends implement the same interface, they handle the same edge cases. If event types follow a naming convention, all event types follow it.

Inconsistency is not a style choice — it's a bug that hasn't caused a failure yet.

## Tests Prove the Work

Code without tests is a hypothesis. If you add a feature, add a test that exercises it. If you fix a bug, add a test that would have caught it. Run the existing tests before you commit. If they fail, you're not done.

## Commits Are Atomic

Each commit does one thing and does it completely. The commit that adds a type also exports it, documents it, and tests it. The commit that moves code from one file to another updates every reference. Half-done work is not committable.

## Finish What You Start

Do not declare a task complete after implementing the easy part. If the requirement is an end-to-end flow with seven steps, delivering step one is not done — it is started. The hard work is in the state transitions, the multi-party handoffs, the steps where things actually break. A test that covers issuance but not transfer or burn is not an e2e test. It is a unit test with a misleading name.

When the scope is clear — and especially when you were told to model after an existing test that covers the full lifecycle — implement the full lifecycle.

## Leave It Better

Every session should leave the codebase cleaner than it started. Not just in the area you were asked to work on — everywhere your work touched. If you opened a file and saw something wrong, it should be fixed by the time you close it.
