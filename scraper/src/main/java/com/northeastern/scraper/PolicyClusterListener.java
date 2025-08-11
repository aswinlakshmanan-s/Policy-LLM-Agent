package com.northeastern.scraper;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.ClusterEvent;
import akka.cluster.Member;
import akka.cluster.typed.Cluster;
import akka.cluster.typed.Subscribe;

import java.util.stream.StreamSupport;

/**
 * Actor that listens to cluster membership events for the policy bot.
 * Monitors when backend and frontend nodes join/leave the cluster.
 */
public class PolicyClusterListener extends AbstractBehavior<ClusterEvent.ClusterDomainEvent> {

    public static Behavior<ClusterEvent.ClusterDomainEvent> create() {
        return Behaviors.setup(PolicyClusterListener::new);
    }

    private final Cluster cluster;

    private PolicyClusterListener(ActorContext<ClusterEvent.ClusterDomainEvent> context) {
        super(context);
        this.cluster = Cluster.get(context.getSystem());

        // Subscribe to cluster events
        cluster.subscriptions().tell(Subscribe.create(
                context.getSelf(),
                ClusterEvent.ClusterDomainEvent.class
        ));

        System.out.println("[CLUSTER] 👂 Monitoring cluster events on " + cluster.selfMember().address());
    }

    @Override
    public Receive<ClusterEvent.ClusterDomainEvent> createReceive() {
        return newReceiveBuilder()
                .onMessage(ClusterEvent.MemberUp.class, this::onMemberUp)
                .onMessage(ClusterEvent.MemberRemoved.class, this::onMemberRemoved)
                .onMessage(ClusterEvent.MemberLeft.class, this::onMemberLeft)
                .onMessage(ClusterEvent.UnreachableMember.class, this::onUnreachableMember)
                .onMessage(ClusterEvent.ReachableMember.class, this::onReachableMember)
                .onAnyMessage(this::onAnyOtherEvent)
                .build();
    }

    private Behavior<ClusterEvent.ClusterDomainEvent> onMemberUp(ClusterEvent.MemberUp event) {
        Member member = event.member();
        System.out.println(String.format(
                "🟢 Policy Cluster: Member UP - %s with roles %s",
                member.address(),
                member.getRoles()
        ));

        // Announce specific node types
        if (member.hasRole("backend")) {
            System.out.println("🔧 Backend processing node is now available for policy queries");
        } else if (member.hasRole("frontend")) {
            System.out.println("🌐 Frontend node is ready to accept user queries");
        }

        long clusterSize = StreamSupport.stream(cluster.state().getMembers().spliterator(), false).count();
        System.out.println("📊 Policy cluster size: " + clusterSize + " nodes");

        return this;
    }

    private Behavior<ClusterEvent.ClusterDomainEvent> onMemberRemoved(ClusterEvent.MemberRemoved event) {
        Member member = event.member();
        System.out.println(String.format(
                "🔴 Policy Cluster: Member REMOVED - %s with roles %s",
                member.address(),
                member.getRoles()
        ));

        if (member.hasRole("backend")) {
            System.out.println("⚠️ Backend processing capacity reduced");
        }

        long clusterSize = StreamSupport.stream(cluster.state().getMembers().spliterator(), false).count();
        System.out.println("📊 Policy cluster size: " + clusterSize + " nodes");

        return this;
    }

    private Behavior<ClusterEvent.ClusterDomainEvent> onMemberLeft(ClusterEvent.MemberLeft event) {
        Member member = event.member();
        System.out.println(String.format(
                "🟡 Policy Cluster: Member LEFT - %s with roles %s",
                member.address(),
                member.getRoles()
        ));
        return this;
    }

    private Behavior<ClusterEvent.ClusterDomainEvent> onUnreachableMember(ClusterEvent.UnreachableMember event) {
        Member member = event.member();
        System.out.println(String.format(
                "⚠️ Policy Cluster: Member UNREACHABLE - %s with roles %s",
                member.address(),
                member.getRoles()
        ));

        if (member.hasRole("backend")) {
            System.out.println("⚠️ Policy processing may be affected - backend unreachable");
        }

        return this;
    }

    private Behavior<ClusterEvent.ClusterDomainEvent> onReachableMember(ClusterEvent.ReachableMember event) {
        Member member = event.member();
        System.out.println(String.format(
                "✅ Policy Cluster: Member REACHABLE - %s with roles %s",
                member.address(),
                member.getRoles()
        ));

        if (member.hasRole("backend")) {
            System.out.println("✅ Policy processing restored - backend reachable");
        }

        return this;
    }

    private Behavior<ClusterEvent.ClusterDomainEvent> onAnyOtherEvent(ClusterEvent.ClusterDomainEvent event) {
        System.out.println("📋 Policy Cluster event: " + event.getClass().getSimpleName());
        return this;
    }
}