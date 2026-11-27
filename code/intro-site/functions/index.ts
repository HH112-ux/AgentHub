// 边缘运行时要求函数包根目录有一个 index.ts 作为入口，并用 Deno.serve 启动，
// 不用 Express 之类的常驻进程写法。业务逻辑全在 chat.mjs，入口刻意薄到只有一行装配。
import { createRequestHandler } from './chat.mjs';

const handle = createRequestHandler();

Deno.serve(handle);
