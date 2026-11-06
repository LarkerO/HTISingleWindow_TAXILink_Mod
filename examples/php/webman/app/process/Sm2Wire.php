<?php
declare(strict_types=1);

namespace app\process;

final class Sm2Wire
{
    private array $nonces = [];

    public function __construct(
        private readonly string $openssl,
        private readonly string $privateKey,
        private readonly string $publicKey
    ) {
        if (!is_file($privateKey) || !is_file($publicKey)) {
            throw new \RuntimeException('Missing gateway SM2 private key or pinned mod public key');
        }
        $this->sign('gateway', '{}');
    }

    public function sign(string $role, string $json): string
    {
        $ts = (int) floor(microtime(true) * 1000);
        $nonce = bin2hex(random_bytes(16));
        $body = base64_encode($json);
        $sig = base64_encode($this->crypt("TAXILink-v2\n$role\n$ts\n$nonce\n$body"));
        return json_encode(compact('ts', 'nonce', 'body', 'sig'), JSON_THROW_ON_ERROR);
    }

    public function verify(string $role, string $wire): string
    {
        $e = json_decode($wire, true, 8, JSON_THROW_ON_ERROR);
        $now = (int) floor(microtime(true) * 1000);
        $ts = $e['ts'] ?? null;
        $nonce = $e['nonce'] ?? null;
        $body = $e['body'] ?? null;
        $sig = is_string($e['sig'] ?? null) ? base64_decode($e['sig'], true) : false;
        foreach ($this->nonces as $key => $expiry) {
            if ($expiry < $now) unset($this->nonces[$key]);
        }
        if (!is_int($ts) || abs($now - $ts) > 30000 || !is_string($nonce) || !preg_match('/^[a-f0-9]{32}$/D', $nonce)
            || !is_string($body) || $sig === false || strlen($sig) > 80 || isset($this->nonces[$nonce]) || count($this->nonces) >= 10000) {
            throw new \UnexpectedValueException('Invalid or replayed SM2 envelope');
        }
        $decoded = base64_decode($body, true);
        if ($decoded === false) throw new \UnexpectedValueException('Invalid SM2 payload');
        $this->crypt("TAXILink-v2\n$role\n$ts\n$nonce\n$body", $sig);
        $this->nonces[$nonce] = $ts + 30000;
        return $decoded;
    }

    private function crypt(string $message, ?string $signature = null): string
    {
        $input = tempnam(sys_get_temp_dir(), 'tlmsg');
        $sig = tempnam(sys_get_temp_dir(), 'tlsig');
        $process = null;
        try {
            if ($input === false || $sig === false || file_put_contents($input, $message) === false)
                throw new \RuntimeException('Cannot prepare SM2 operation');
            $args = [$this->openssl, 'dgst', '-sm3', '-sigopt', 'distid:TAXILink-v2'];
            if ($signature === null) array_push($args, '-sign', $this->privateKey, '-passin', 'pass:', '-out', $sig);
            else {
                if (file_put_contents($sig, $signature) === false) throw new \RuntimeException('Cannot prepare SM2 signature');
                array_push($args, '-verify', $this->publicKey, '-signature', $sig);
            }
            $args[] = $input;
            $null = PHP_OS_FAMILY === 'Windows' ? 'NUL' : '/dev/null';
            $process = proc_open($args, [0 => ['file', $null, 'r'], 1 => ['file', $null, 'w'], 2 => ['file', $null, 'w']], $pipes);
            if (!is_resource($process)) throw new \RuntimeException('Cannot start OpenSSL');
            $deadline = hrtime(true) + 3_000_000_000;
            do {
                $status = proc_get_status($process);
                if (!$status['running']) break;
                usleep(1000);
            } while (hrtime(true) < $deadline);
            if ($status['running'] || $status['exitcode'] !== 0)
                throw new \UnexpectedValueException('SM2 signing or verification failed');
            return $signature === null ? file_get_contents($sig) : '';
        } finally {
            if (is_resource($process)) {
                if (($status['running'] ?? true)) proc_terminate($process, 9);
                proc_close($process);
            }
            if ($input !== false) unlink($input);
            if ($sig !== false) unlink($sig);
        }
    }
}
