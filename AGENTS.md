# Repository instructions

Use [Conventional Commits 1.0.0](https://www.conventionalcommits.org/zh-hans/v1.0.0/) for every new commit in this repository. Use the format `type(scope): description` with an optional scope. Use `feat` for features, `fix` for bug fixes, and an appropriate type such as `docs`, `test`, or `chore` for other changes. Mark breaking changes with `!` before the colon or a `BREAKING CHANGE:` footer.

Write commit descriptions and bodies in Chinese. Keep the Conventional Commits type and scope in their usual technical form.

## Git 推送频率

默认直接在 `main` 开发和推送，不另建任务分支，除非用户明确要求。

减少 `git push` 频率：同一任务的相关修改优先在本地完成、审查并通过必要检查后，再集中推送一次。可以按逻辑拆分本地提交，但不要每完成一处修改或每创建一个提交就立即推送。

不要为了试错而反复推送触发 CI；先完成本地可执行的验证，再批量提交需要远端验证的修改。仅在用户明确要求立即推送、需要协作同步或必须进行远端验证时追加推送；用户的明确指令优先。
