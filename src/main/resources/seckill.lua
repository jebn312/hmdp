local userId = ARGV[1]
local voucherId = ARGV[2]
local orderId = ARGV[3]

local orderKey = 'seckill:order:' .. voucherId
local stockKey = 'seckill:stock:' .. voucherId

if(tonumber(redis.call('GET', stockKey)) < 1) then
    return 1
end

if(redis.call('SISMEMBER', orderKey, userId) == 1) then
    return 2
end

redis.call('INCRBY', stockKey, -1)
redis.call('SADD', orderKey, userId)
redis.call('XADD', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0;
